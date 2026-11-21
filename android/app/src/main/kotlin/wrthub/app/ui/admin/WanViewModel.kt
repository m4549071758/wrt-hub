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
import wrthub.core.admin.WanConfig
import wrthub.core.admin.WanInput
import wrthub.core.admin.WanProto
import wrthub.core.admin.prefixLength
import wrthub.core.admin.requireValidIpv4

enum class WanField { IPADDR, NETMASK, GATEWAY, DNS, USERNAME, PASSWORD }

/** The WAN form; [password] empty keeps the current PPPoE password. */
data class WanForm(
    val proto: WanProto = WanProto.DHCP,
    val ipaddr: String = "",
    val netmask: String = "255.255.255.0",
    val gateway: String = "",
    /** Comma- or space-separated. */
    val dns: String = "",
    val username: String = "",
    val password: String = "",
    val error: WanField? = null,
)

data class WanUiState(
    val title: String = "",
    val loading: Boolean = false,
    val failure: AdminFailure? = null,
    /** Null until loaded, or when the node has no `wan` interface. */
    val wan: WanConfig? = null,
    val form: WanForm = WanForm(),
    val saving: Boolean = false,
    val notice: AdminNotice? = null,
)

class WanViewModel(
    private val nodeId: String,
    private val session: SiteSession,
    private val adminFor: (NodeConfig) -> NodeAdmin,
) : ViewModel() {
    private val local = MutableStateFlow(WanUiState())

    val state: StateFlow<WanUiState> = combine(session.state, local) { site, ui ->
        ui.copy(title = site.nodes.firstOrNull { it.config.id == nodeId }?.config?.label.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WanUiState())

    init {
        viewModelScope.launch {
            session.awaitNode(nodeId)
            load()
        }
    }

    fun refresh() = load()

    fun onForm(change: (WanForm) -> WanForm) = local.update { it.copy(form = change(it.form).copy(error = null)) }

    fun save() {
        val admin = admin() ?: return
        val form = local.value.form
        val error = validate(form, hasPassword = local.value.wan?.hasPassword == true)
        if (error != null) {
            local.update { it.copy(form = form.copy(error = error)) }
            return
        }
        if (local.value.saving) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = adminCall { admin.saveWan(form.toInput()) }
            val reloaded = adminCall { admin.wan() }.getOrNull()
            local.update { ui ->
                ui.copy(
                    saving = false,
                    wan = reloaded ?: ui.wan,
                    form = reloaded?.let(::formOf) ?: ui.form,
                    notice = result.fold({ AdminNotice.Written(it) }, { AdminNotice.Failed(it.adminFailure) }),
                )
            }
        }
    }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    private fun admin(): NodeAdmin? = session.state.value.nodes.firstOrNull { it.config.id == nodeId }?.config?.let(adminFor)

    private fun load() {
        val admin = admin() ?: return
        local.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            val result = adminCall { admin.wan() }
            local.update { ui ->
                result.fold(
                    { wan -> ui.copy(loading = false, wan = wan, form = wan?.let(::formOf) ?: ui.form) },
                    { ui.copy(loading = false, failure = it.adminFailure) },
                )
            }
        }
    }
}

private fun formOf(wan: WanConfig) = WanForm(
    proto = wan.proto ?: WanProto.DHCP,
    ipaddr = wan.ipaddr.orEmpty(),
    netmask = wan.netmask ?: "255.255.255.0",
    gateway = wan.gateway.orEmpty(),
    dns = wan.dns.joinToString(" "),
    username = wan.username.orEmpty(),
)

private fun WanForm.dnsList() = dns.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }

/** A blank PPPoE password keeps the stored one, so it is required only when none is stored. */
private fun validate(form: WanForm, hasPassword: Boolean): WanField? = when (form.proto) {
    WanProto.DHCP -> null
    WanProto.STATIC -> when {
        !isValid { requireValidIpv4(form.ipaddr.trim()) } -> WanField.IPADDR
        prefixLength(form.netmask.trim()) == null -> WanField.NETMASK
        !isValid { requireValidIpv4(form.gateway.trim()) } -> WanField.GATEWAY
        !form.dnsList().all { isValid { requireValidIpv4(it) } } -> WanField.DNS
        else -> null
    }
    WanProto.PPPOE -> when {
        form.username.isBlank() -> WanField.USERNAME
        form.password.isEmpty() && !hasPassword -> WanField.PASSWORD
        form.password.any(Char::isISOControl) -> WanField.PASSWORD
        else -> null
    }
}

private fun WanForm.toInput(): WanInput = when (proto) {
    WanProto.DHCP -> WanInput.Dhcp
    WanProto.STATIC -> WanInput.Static(ipaddr.trim(), netmask.trim(), gateway.trim(), dnsList())
    WanProto.PPPOE -> WanInput.Pppoe(username.trim(), password.ifEmpty { null })
}
