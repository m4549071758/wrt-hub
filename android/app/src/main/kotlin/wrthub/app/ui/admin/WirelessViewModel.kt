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
import wrthub.core.admin.Radio
import wrthub.core.admin.RadioCapabilities
import wrthub.core.admin.RadioInput
import wrthub.core.admin.Ssid
import wrthub.core.admin.htmodesFor
import wrthub.core.direct.WriteOutcome

data class RadioView(val radio: Radio, val capabilities: RadioCapabilities)

/** Editing one radio; mesh radios only offer the power. */
data class RadioEditor(val radio: String, val channel: String, val htmode: String?, val txpower: Int?)

data class WirelessUiState(
    val title: String = "",
    val loading: Boolean = false,
    val failure: AdminFailure? = null,
    val radios: List<RadioView> = emptyList(),
    val ssids: List<Ssid> = emptyList(),
    val editor: RadioEditor? = null,
    val saving: Boolean = false,
    val notice: AdminNotice? = null,
)

class WirelessViewModel(
    private val nodeId: String,
    private val session: SiteSession,
    private val adminFor: (NodeConfig) -> NodeAdmin,
) : ViewModel() {
    private val local = MutableStateFlow(WirelessUiState())

    val state: StateFlow<WirelessUiState> = combine(session.state, local) { site, ui ->
        ui.copy(title = site.nodes.firstOrNull { it.config.id == nodeId }?.config?.label.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WirelessUiState())

    init {
        viewModelScope.launch {
            session.awaitNode(nodeId)
            load()
        }
    }

    fun refresh() = load()

    fun edit(radio: Radio) = local.update { it.copy(editor = RadioEditor(radio.name, radio.channel, radio.htmode, radio.txpower)) }

    /** A width the new channel cannot use falls back to the widest one it can. */
    fun onChannel(channel: String) = local.update { ui ->
        val editor = ui.editor ?: return@update ui
        val allowed = ui.radios.firstOrNull { it.radio.name == editor.radio }?.capabilities?.htmodesFor(channel).orEmpty()
        val htmode = editor.htmode?.takeIf { it in allowed } ?: allowed.lastOrNull() ?: editor.htmode
        ui.copy(editor = editor.copy(channel = channel, htmode = htmode))
    }

    fun onHtmode(htmode: String) = local.update { it.copy(editor = it.editor?.copy(htmode = htmode)) }

    fun onTxpower(txpower: Int?) = local.update { it.copy(editor = it.editor?.copy(txpower = txpower)) }

    fun closeEditor() = local.update { it.copy(editor = null) }

    fun save() {
        val editor = local.value.editor ?: return
        write { it.saveRadio(editor.radio, RadioInput(editor.channel, editor.htmode, editor.txpower)) }
    }

    fun setSsidEnabled(section: String, enabled: Boolean) = write { it.setSsidEnabled(section, enabled) }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    private fun admin(): NodeAdmin? = session.state.value.nodes.firstOrNull { it.config.id == nodeId }?.config?.let(adminFor)

    private fun write(call: suspend (NodeAdmin) -> WriteOutcome) {
        val admin = admin() ?: return
        if (local.value.saving) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = adminCall { call(admin) }
            val reloaded = adminCall { admin.wireless() }.getOrNull()
            local.update { ui ->
                ui.copy(
                    saving = false,
                    editor = if (result.isSuccess) null else ui.editor,
                    radios = reloaded?.radios?.map { radio -> RadioView(radio, ui.radios.firstOrNull { it.radio.name == radio.name }?.capabilities ?: NO_CAPABILITIES) }
                        ?: ui.radios,
                    ssids = reloaded?.ssids ?: ui.ssids,
                    notice = result.fold({ AdminNotice.Written(it) }, { AdminNotice.Failed(it.adminFailure) }),
                )
            }
        }
    }

    private fun load() {
        val admin = admin() ?: return
        local.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            val result = adminCall {
                val config = admin.wireless()
                config.radios.map { RadioView(it, admin.radioCapabilities(it.name)) } to config.ssids
            }
            local.update { ui ->
                result.fold(
                    { (radios, ssids) -> ui.copy(loading = false, radios = radios, ssids = ssids) },
                    { ui.copy(loading = false, failure = it.adminFailure) },
                )
            }
        }
    }
}

private val NO_CAPABILITIES = RadioCapabilities(emptyList(), emptyList(), emptyList())
