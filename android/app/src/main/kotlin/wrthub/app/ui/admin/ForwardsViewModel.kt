package wrthub.app.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import wrthub.app.data.NodeConfig
import wrthub.app.domain.AdminFailure
import wrthub.app.domain.NodeAdmin
import wrthub.app.domain.SiteSession
import wrthub.app.domain.adminCall
import wrthub.app.domain.adminFailure
import wrthub.app.domain.awaitNode
import wrthub.app.domain.siteTopology
import wrthub.core.admin.ForwardProtocol
import wrthub.core.admin.PortForward
import wrthub.core.admin.PortForwardInput
import wrthub.core.admin.requireValidIpv4
import wrthub.core.admin.requireValidPortRange
import wrthub.core.direct.WriteOutcome
import wrthub.core.topology.Presence

enum class ForwardField { NAME, EXTERNAL_PORT, INTERNAL_IP, INTERNAL_PORT }

/** [section] is null for a new forward. */
data class ForwardEditor(
    val section: String?,
    val name: String = "",
    val protocol: ForwardProtocol = ForwardProtocol.TCP,
    val externalPort: String = "",
    val internalIp: String = "",
    val internalPort: String = "",
    val error: ForwardField? = null,
)

/** An online client offered as the forward's target. */
data class ForwardTarget(val label: String, val ip: String)

data class ForwardsUiState(
    val title: String = "",
    val loading: Boolean = false,
    val failure: AdminFailure? = null,
    val forwards: List<PortForward>? = null,
    val targets: List<ForwardTarget> = emptyList(),
    val editor: ForwardEditor? = null,
    val saving: Boolean = false,
    val notice: AdminNotice? = null,
)

class ForwardsViewModel(
    private val nodeId: String,
    private val session: SiteSession,
    private val adminFor: (NodeConfig) -> NodeAdmin,
) : ViewModel() {
    private val local = MutableStateFlow(ForwardsUiState())

    val state: StateFlow<ForwardsUiState> = combine(session.state, local) { site, ui ->
        val targets = siteTopology(site.nodes).clients
            .filter { it.ipv4 != null && (it.presence == Presence.WIRELESS || it.presence == Presence.ONLINE_UNKNOWN_LINK) }
            .map { ForwardTarget(it.name ?: it.mac, it.ipv4!!) }
            .sortedBy { it.label.lowercase() }
        ui.copy(title = site.nodes.firstOrNull { it.config.id == nodeId }?.config?.label.orEmpty(), targets = targets)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ForwardsUiState())

    init {
        viewModelScope.launch {
            session.awaitNode(nodeId)
            load()
        }
    }

    fun refresh() = load()

    fun add() = local.update { it.copy(editor = ForwardEditor(section = null)) }

    fun edit(forward: PortForward) {
        if (!forward.editable) return
        local.update {
            it.copy(
                editor = ForwardEditor(
                    section = forward.section,
                    name = forward.name.orEmpty(),
                    protocol = forward.protocol ?: ForwardProtocol.BOTH,
                    externalPort = forward.externalPort.orEmpty(),
                    internalIp = forward.internalIp.orEmpty(),
                    internalPort = forward.internalPort.orEmpty(),
                ),
            )
        }
    }

    fun onEditor(change: (ForwardEditor) -> ForwardEditor) = local.update { it.copy(editor = it.editor?.let(change)?.copy(error = null)) }

    fun closeEditor() = local.update { it.copy(editor = null) }

    fun save() {
        val editor = local.value.editor ?: return
        val internalPort = editor.internalPort.trim().ifEmpty { editor.externalPort.trim() }
        val name = editor.name.trim()
        val error = when {
            name.length > 64 || name.any(Char::isISOControl) -> ForwardField.NAME
            !isValid { requireValidPortRange(editor.externalPort.trim()) } -> ForwardField.EXTERNAL_PORT
            !isValid { requireValidIpv4(editor.internalIp.trim()) } -> ForwardField.INTERNAL_IP
            !isValid { requireValidPortRange(internalPort) } -> ForwardField.INTERNAL_PORT
            else -> null
        }
        if (error != null) {
            local.update { it.copy(editor = editor.copy(error = error)) }
            return
        }
        val input = PortForwardInput(name.ifEmpty { null }, editor.protocol, editor.externalPort.trim(), editor.internalIp.trim(), internalPort)
        write { it.savePortForward(editor.section, input) }
    }

    fun setEnabled(section: String, enabled: Boolean) = write { it.setPortForwardEnabled(section, enabled) }

    fun delete(section: String) = write { it.deletePortForward(section) }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    private fun admin(): NodeAdmin? = session.state.value.nodes.firstOrNull { it.config.id == nodeId }?.config?.let(adminFor)

    private fun write(call: suspend (NodeAdmin) -> WriteOutcome) {
        val admin = admin() ?: return
        if (local.value.saving) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = adminCall { call(admin) }
            val reloaded = adminCall { admin.portForwards() }.getOrNull()
            local.update { ui ->
                ui.copy(
                    saving = false,
                    editor = if (result.isSuccess) null else ui.editor,
                    forwards = reloaded ?: ui.forwards,
                    notice = result.fold({ AdminNotice.Written(it) }, { AdminNotice.Failed(it.adminFailure) }),
                )
            }
        }
    }

    private fun load() {
        val admin = admin() ?: return
        local.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            val result = adminCall { admin.portForwards() }
            local.update { ui ->
                result.fold({ ui.copy(loading = false, forwards = it) }, { ui.copy(loading = false, failure = it.adminFailure) })
            }
        }
    }
}
