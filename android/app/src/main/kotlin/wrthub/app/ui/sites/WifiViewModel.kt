package wrthub.app.ui.sites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import wrthub.app.data.NodeConfig
import wrthub.app.data.SiteConfig
import wrthub.app.domain.NodeOperations
import wrthub.app.domain.NodeState
import wrthub.app.domain.SiteSession
import wrthub.core.direct.requireValidPsk
import wrthub.core.direct.requireValidSsid
import wrthub.core.model.WirelessMode
import wrthub.core.model.wirelessInterfaces
import wrthub.core.ubus.UbusException

enum class WifiInputError { SSID, KEY }

sealed interface WifiOutcome {
    data class Changed(val interfaces: Int) : WifiOutcome

    data object NoAccessPoint : WifiOutcome

    /** Staged fine but not applied because another node failed, so the site keeps one consistent Wi-Fi. */
    data object NotApplied : WifiOutcome

    data class Failed(val reason: String) : WifiOutcome
}

data class WifiNodeResult(val label: String, val outcome: WifiOutcome)

data class WifiUiState(
    val site: SiteConfig? = null,
    val currentSsids: List<String> = emptyList(),
    val ssid: String = "",
    val key: String = "",
    val applying: Boolean = false,
    val inputError: WifiInputError? = null,
    val results: List<WifiNodeResult>? = null,
)

/**
 * Changes the AP SSID and key on every node of a site. The phone doing this usually loses its Wi-Fi as soon as the
 * node it is connected to applies, so all nodes are staged first and then applied together; if any node fails to
 * stage, nothing is applied and the staged changes are discarded.
 */
class WifiViewModel(
    private val siteId: String,
    private val session: SiteSession,
    private val operationsFor: (NodeConfig) -> NodeOperations,
) : ViewModel() {
    private data class Local(
        val ssid: String = "",
        val key: String = "",
        val applying: Boolean = false,
        val inputError: WifiInputError? = null,
        val results: List<WifiNodeResult>? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<WifiUiState> = combine(session.state, local) { site, ui ->
        val current = if (site.site?.id == siteId) apSsids(site.nodes) else emptyList()
        WifiUiState(site.sites.firstOrNull { it.id == siteId }, current, ui.ssid, ui.key, ui.applying, ui.inputError, ui.results)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WifiUiState())

    init {
        if (session.state.value.site?.id != siteId) session.select(siteId)
    }

    fun onSsid(value: String) = local.update { it.copy(ssid = value, inputError = null) }

    fun onKey(value: String) = local.update { it.copy(key = value, inputError = null) }

    /** Checks the input; the screen asks for confirmation only when this returns true. */
    fun validate(): Boolean {
        val input = local.value
        val error = when {
            !isValid { requireValidSsid(input.ssid) } -> WifiInputError.SSID
            !isValid { requireValidPsk(input.key) } -> WifiInputError.KEY
            else -> null
        }
        local.update { it.copy(inputError = error) }
        return error == null
    }

    fun apply() {
        val site = state.value.site ?: return
        if (!validate()) return
        val input = local.value
        local.update { it.copy(applying = true, results = null) }
        viewModelScope.launch {
            val operations = site.nodes.associate { it.id to operationsFor(it) }
            val staged = coroutineScope {
                site.nodes.map { node -> async { node to attempt { operations.getValue(node.id).stageWifi(input.ssid, input.key) } } }.awaitAll()
            }
            val results = if (staged.any { it.second.isFailure }) {
                staged.map { (node, result) ->
                    if (result.isSuccess) operations.getValue(node.id).discardQuietly()
                    WifiNodeResult(node.label, result.exceptionOrNull()?.let { WifiOutcome.Failed(it.reason()) } ?: WifiOutcome.NotApplied)
                }
            } else {
                coroutineScope {
                    staged.map { (node, result) ->
                        async {
                            val interfaces = result.getOrThrow()
                            val outcome = when {
                                interfaces == 0 -> WifiOutcome.NoAccessPoint
                                else -> attempt { operations.getValue(node.id).applyUnchecked() }
                                    .fold({ WifiOutcome.Changed(interfaces) }, { WifiOutcome.Failed(it.reason()) })
                            }
                            WifiNodeResult(node.label, outcome)
                        }
                    }.awaitAll()
                }
            }
            local.update { it.copy(applying = false, results = results, key = "") }
        }
    }

    private suspend fun NodeOperations.discardQuietly() {
        attempt { discardWifi() }
    }
}

private fun apSsids(nodes: List<NodeState>): List<String> =
    nodes.filterIsInstance<NodeState.Online>()
        .flatMap { node -> node.snapshot.wirelessInterfaces().filter { it.mode == WirelessMode.AP }.mapNotNull { it.ssid } }
        .distinct()
        .sorted()

private inline fun isValid(check: () -> Unit): Boolean =
    try {
        check()
        true
    } catch (e: IllegalArgumentException) {
        false
    }

/** Router-call failures become a failed result; anything else, including cancellation, propagates. */
private suspend fun <T> attempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: IOException) {
        Result.failure(e)
    } catch (e: UbusException) {
        Result.failure(e)
    }

private fun Throwable.reason(): String = message ?: javaClass.simpleName
