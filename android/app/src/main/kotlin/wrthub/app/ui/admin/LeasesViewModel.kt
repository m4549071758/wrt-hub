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
import wrthub.core.admin.StaticLease
import wrthub.core.admin.StaticLeaseInput
import wrthub.core.admin.requireValidHostname
import wrthub.core.admin.requireValidIpv4
import wrthub.core.direct.WriteOutcome
import wrthub.core.direct.normalizeMac

/** A client to pin, as handed over from the clients tab. */
data class LeasePrefill(val mac: String, val ip: String?, val name: String?)

enum class LeaseField { NAME, MAC, IP }

/** [section] is null for a new lease. */
data class LeaseEditor(
    val section: String?,
    val name: String = "",
    val mac: String = "",
    val ip: String = "",
    val error: LeaseField? = null,
)

data class LeasesUiState(
    val title: String = "",
    val loading: Boolean = false,
    val failure: AdminFailure? = null,
    /** Null until loaded. */
    val servesDhcp: Boolean? = null,
    val leases: List<StaticLease> = emptyList(),
    val editor: LeaseEditor? = null,
    val saving: Boolean = false,
    val notice: AdminNotice? = null,
)

class LeasesViewModel(
    private val nodeId: String,
    private val session: SiteSession,
    private val adminFor: (NodeConfig) -> NodeAdmin,
    private val prefill: LeasePrefill? = null,
) : ViewModel() {
    private val local = MutableStateFlow(LeasesUiState())

    val state: StateFlow<LeasesUiState> = combine(session.state, local) { site, ui ->
        ui.copy(title = site.nodes.firstOrNull { it.config.id == nodeId }?.config?.label.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LeasesUiState())

    init {
        viewModelScope.launch {
            session.awaitNode(nodeId)
            load(openPrefill = true)
        }
    }

    fun refresh() = load(openPrefill = false)

    fun add() = local.update { it.copy(editor = LeaseEditor(section = null)) }

    fun edit(lease: StaticLease) {
        if (!lease.editable) return
        local.update { it.copy(editor = LeaseEditor(lease.section, lease.name.orEmpty(), lease.macs.single(), lease.ip.orEmpty())) }
    }

    fun onName(value: String) = local.update { it.copy(editor = it.editor?.copy(name = value, error = null)) }

    fun onMac(value: String) = local.update { it.copy(editor = it.editor?.copy(mac = value, error = null)) }

    fun onIp(value: String) = local.update { it.copy(editor = it.editor?.copy(ip = value, error = null)) }

    fun closeEditor() = local.update { it.copy(editor = null) }

    fun save() {
        val editor = local.value.editor ?: return
        val error = when {
            editor.name.isNotBlank() && !isValid { requireValidHostname(editor.name.trim()) } -> LeaseField.NAME
            !isValid { normalizeMac(editor.mac) } -> LeaseField.MAC
            !isValid { requireValidIpv4(editor.ip.trim()) } -> LeaseField.IP
            else -> null
        }
        if (error != null) {
            local.update { it.copy(editor = editor.copy(error = error)) }
            return
        }
        val input = StaticLeaseInput(editor.name.trim().ifEmpty { null }, editor.mac.trim(), editor.ip.trim())
        write { it.saveStaticLease(editor.section, input) }
    }

    fun delete(section: String) = write { it.deleteStaticLease(section) }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    private fun admin(): NodeAdmin? = session.state.value.nodes.firstOrNull { it.config.id == nodeId }?.config?.let(adminFor)

    private fun write(call: suspend (NodeAdmin) -> WriteOutcome) {
        val admin = admin() ?: return
        if (local.value.saving) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = adminCall { call(admin) }
            val reloaded = adminCall { admin.dhcpConfig() }.getOrNull()
            local.update { ui ->
                ui.copy(
                    saving = false,
                    // Keep the editor open on failure so the input is not lost.
                    editor = if (result.isSuccess) null else ui.editor,
                    leases = reloaded?.leases ?: ui.leases,
                    notice = result.fold({ AdminNotice.Written(it) }, { AdminNotice.Failed(it.adminFailure) }),
                )
            }
            if (result.isSuccess) session.refresh()
        }
    }

    private fun load(openPrefill: Boolean) {
        val admin = admin() ?: return
        local.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            val result = adminCall { admin.dhcpConfig() }
            local.update { ui ->
                result.fold(
                    { config ->
                        val editor = if (openPrefill && config.servesDhcp) prefill?.let { editorFor(it, config.leases) } else ui.editor
                        ui.copy(loading = false, servesDhcp = config.servesDhcp, leases = config.leases, editor = editor)
                    },
                    { ui.copy(loading = false, failure = it.adminFailure) },
                )
            }
        }
    }

    /** Pinning a client that already has a lease edits that lease instead of adding a conflicting one. */
    private fun editorFor(prefill: LeasePrefill, leases: List<StaticLease>): LeaseEditor {
        val mac = prefill.mac.lowercase()
        val existing = leases.firstOrNull { mac in it.macs && it.editable }
        return existing?.let { LeaseEditor(it.section, it.name.orEmpty(), mac, it.ip.orEmpty()) }
            ?: LeaseEditor(null, prefill.name?.takeIf { isValid { requireValidHostname(it) } }.orEmpty(), mac, prefill.ip.orEmpty())
    }
}

internal inline fun isValid(check: () -> Unit): Boolean =
    try {
        check()
        true
    } catch (e: IllegalArgumentException) {
        false
    }
