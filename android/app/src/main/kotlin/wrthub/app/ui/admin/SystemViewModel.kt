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
import wrthub.app.domain.NodeState
import wrthub.app.domain.SiteSession
import wrthub.app.domain.adminCall
import wrthub.app.domain.adminFailure
import wrthub.app.domain.awaitNode
import wrthub.core.admin.Led
import wrthub.core.admin.LedInput
import wrthub.core.admin.SystemConfig
import wrthub.core.admin.SystemInput
import wrthub.core.admin.requireValidHostname
import wrthub.core.direct.WriteOutcome

enum class SystemField { HOSTNAME, ZONE, NTP }

data class SystemForm(
    val hostname: String = "",
    val zonename: String = "",
    val ntpEnabled: Boolean = true,
    /** One server per line. */
    val ntpServers: String = "",
    val error: SystemField? = null,
)

data class LedEditor(val name: String, val trigger: String, val device: String)

data class SystemUiState(
    val title: String = "",
    val loading: Boolean = false,
    val failure: AdminFailure? = null,
    val config: SystemConfig? = null,
    val form: SystemForm = SystemForm(),
    val zones: List<String> = emptyList(),
    val leds: List<Led> = emptyList(),
    val ledEditor: LedEditor? = null,
    /** Network devices offered to a netdev LED trigger. */
    val devices: List<String> = emptyList(),
    /** The crontab as loaded; [cron] is the edited text. */
    val savedCron: String = "",
    val cron: String = "",
    val saving: Boolean = false,
    val notice: AdminNotice? = null,
)

/** Host name, time, LEDs and the crontab of one node. */
class SystemViewModel(
    private val nodeId: String,
    private val session: SiteSession,
    private val adminFor: (NodeConfig) -> NodeAdmin,
) : ViewModel() {
    private val local = MutableStateFlow(SystemUiState())

    val state: StateFlow<SystemUiState> = combine(session.state, local) { site, ui ->
        val node = site.nodes.firstOrNull { it.config.id == nodeId }
        val devices = (node as? NodeState.Online)?.snapshot?.devices?.keys.orEmpty().filterNot { it == "lo" }.sorted()
        ui.copy(title = node?.config?.label.orEmpty(), devices = devices)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SystemUiState())

    init {
        viewModelScope.launch {
            session.awaitNode(nodeId)
            load()
        }
    }

    fun refresh() = load()

    fun onForm(change: (SystemForm) -> SystemForm) = local.update { it.copy(form = change(it.form).copy(error = null)) }

    fun saveSystem() {
        val ui = local.value
        val form = ui.form
        val servers = form.ntpServers.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val error = when {
            !isValid { requireValidHostname(form.hostname.trim()) } -> SystemField.HOSTNAME
            form.zonename !in ui.zones -> SystemField.ZONE
            servers.size > 8 || servers.any { it.startsWith("-") || it.any(Char::isWhitespace) } -> SystemField.NTP
            else -> null
        }
        if (error != null) {
            local.update { it.copy(form = form.copy(error = error)) }
            return
        }
        write({ it.saveSystem(SystemInput(form.hostname.trim(), form.zonename, form.ntpEnabled, servers)) }) { admin ->
            val config = admin.system()
            local.update { it.copy(config = config, form = formOf(config)) }
        }
    }

    fun editLed(led: Led) = local.update {
        it.copy(ledEditor = LedEditor(led.name, led.activeTrigger ?: "none", led.device.orEmpty()))
    }

    fun onLed(change: (LedEditor) -> LedEditor) = local.update { it.copy(ledEditor = it.ledEditor?.let(change)) }

    fun closeLed() = local.update { it.copy(ledEditor = null) }

    fun saveLed() {
        val editor = local.value.ledEditor ?: return
        val device = editor.device.takeIf { editor.trigger == "netdev" }
        write({ it.saveLed(editor.name, LedInput(editor.trigger, device)) }) { admin ->
            val leds = admin.leds()
            local.update { it.copy(leds = leds, ledEditor = null) }
        }
    }

    fun onCron(text: String) = local.update { it.copy(cron = text) }

    /** The crontab is a plain file: written at once, no rollback. */
    fun saveCron() {
        val text = local.value.cron
        write({
            it.saveCrontab(text)
            WriteOutcome.APPLIED
        }) { admin ->
            val cron = admin.crontab()
            local.update { it.copy(savedCron = cron, cron = cron) }
        }
    }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    private fun admin(): NodeAdmin? = session.state.value.nodes.firstOrNull { it.config.id == nodeId }?.config?.let(adminFor)

    /** Runs a write, then [reload]s what it touched; editors stay open when the write fails. */
    private fun write(call: suspend (NodeAdmin) -> WriteOutcome, reload: suspend (NodeAdmin) -> Unit) {
        val admin = admin() ?: return
        if (local.value.saving) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = adminCall { call(admin) }
            if (result.isSuccess) adminCall { reload(admin) }
            local.update { ui ->
                ui.copy(saving = false, notice = result.fold({ AdminNotice.Written(it) }, { AdminNotice.Failed(it.adminFailure) }))
            }
        }
    }

    private fun load() {
        val admin = admin() ?: return
        local.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            val result = adminCall { Loaded(admin.system(), admin.timezones().keys.sorted(), admin.leds(), admin.crontab()) }
            local.update { ui ->
                result.fold(
                    { l -> ui.copy(loading = false, config = l.config, form = formOf(l.config), zones = l.zones, leds = l.leds, savedCron = l.cron, cron = l.cron) },
                    { ui.copy(loading = false, failure = it.adminFailure) },
                )
            }
        }
    }

    private data class Loaded(val config: SystemConfig, val zones: List<String>, val leds: List<Led>, val cron: String)
}

private fun formOf(config: SystemConfig) = SystemForm(
    hostname = config.hostname,
    zonename = config.zonename ?: "UTC",
    ntpEnabled = config.ntpEnabled,
    ntpServers = config.ntpServers.joinToString("\n"),
)
