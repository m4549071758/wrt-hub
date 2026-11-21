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
import wrthub.app.R
import wrthub.app.data.NodeConfig
import wrthub.app.domain.AdminFailure
import wrthub.app.domain.NodeAdmin
import wrthub.app.domain.SiteSession
import wrthub.app.domain.adminCall
import wrthub.app.domain.awaitNode
import wrthub.app.domain.adminFailure
import wrthub.core.admin.Connection
import wrthub.core.admin.ConntrackUsage
import wrthub.core.admin.IpFamily
import wrthub.core.admin.LogLine
import wrthub.core.admin.RouteTable
import wrthub.core.admin.Service
import wrthub.core.admin.ServiceAction
import wrthub.core.admin.allowedFor

enum class StatusTab { LOGS, ROUTES, CONNECTIONS, SERVICES }

enum class LogKind { SYSTEM, KERNEL }

data class StatusUiState(
    val title: String = "",
    val tab: StatusTab = StatusTab.LOGS,
    val loading: Boolean = false,
    /** Why the current tab could not be loaded; null while loading or when it loaded. */
    val failure: AdminFailure? = null,
    val logKind: LogKind = LogKind.SYSTEM,
    val logFilter: String = "",
    /** Newest first, already filtered. */
    val logs: List<LogLine> = emptyList(),
    val family: IpFamily = IpFamily.V4,
    val routes: RouteTable? = null,
    val usage: ConntrackUsage? = null,
    /** Largest first. */
    val connections: List<Connection> = emptyList(),
    val services: List<Service> = emptyList(),
    val busyService: String? = null,
    val notice: AdminNotice? = null,
)

/** Read-only status pages of one node, plus starting and stopping its services. */
class StatusViewModel(
    private val nodeId: String,
    private val session: SiteSession,
    private val adminFor: (NodeConfig) -> NodeAdmin,
) : ViewModel() {
    private data class Local(
        val tab: StatusTab = StatusTab.LOGS,
        val loading: Boolean = false,
        val failure: AdminFailure? = null,
        val logKind: LogKind = LogKind.SYSTEM,
        val logFilter: String = "",
        val logs: List<LogLine> = emptyList(),
        val family: IpFamily = IpFamily.V4,
        val routes: RouteTable? = null,
        val usage: ConntrackUsage? = null,
        val connections: List<Connection> = emptyList(),
        val services: List<Service> = emptyList(),
        val busyService: String? = null,
        val notice: AdminNotice? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<StatusUiState> = combine(session.state, local) { site, ui ->
        val filter = ui.logFilter.trim()
        StatusUiState(
            title = site.nodes.firstOrNull { it.config.id == nodeId }?.config?.label.orEmpty(),
            tab = ui.tab,
            loading = ui.loading,
            failure = ui.failure,
            logKind = ui.logKind,
            logFilter = ui.logFilter,
            logs = ui.logs.asReversed().filter { filter.isEmpty() || it.message.contains(filter, ignoreCase = true) },
            family = ui.family,
            routes = ui.routes,
            usage = ui.usage,
            connections = ui.connections,
            services = ui.services,
            busyService = ui.busyService,
            notice = ui.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StatusUiState())

    init {
        viewModelScope.launch {
            session.awaitNode(nodeId)
            load()
        }
    }

    fun selectTab(tab: StatusTab) {
        if (tab == local.value.tab) return
        local.update { it.copy(tab = tab) }
        load()
    }

    fun setLogKind(kind: LogKind) {
        if (kind == local.value.logKind) return
        local.update { it.copy(logKind = kind, logs = emptyList()) }
        load()
    }

    fun setLogFilter(value: String) = local.update { it.copy(logFilter = value) }

    fun setFamily(family: IpFamily) {
        if (family == local.value.family) return
        local.update { it.copy(family = family, routes = null) }
        load()
    }

    fun refresh() = load()

    fun runServiceAction(name: String, action: ServiceAction) {
        val admin = admin() ?: return
        if (!action.allowedFor(name) || local.value.busyService != null) return
        local.update { it.copy(busyService = name) }
        viewModelScope.launch {
            val result = adminCall { admin.serviceAction(name, action) }
            val services = adminCall { admin.services() }.getOrNull()
            local.update { ui ->
                ui.copy(
                    busyService = null,
                    services = services ?: ui.services,
                    notice = result.fold({ AdminNotice.ServiceDone(name, action.label) }, { AdminNotice.Failed(it.adminFailure) }),
                )
            }
        }
    }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    private fun admin(): NodeAdmin? = session.state.value.nodes.firstOrNull { it.config.id == nodeId }?.config?.let(adminFor)

    private fun load() {
        val admin = admin() ?: return
        val ui = local.value
        local.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            val result: Result<(Local) -> Local> = when (ui.tab) {
                StatusTab.LOGS -> adminCall { if (ui.logKind == LogKind.SYSTEM) admin.syslog() else admin.kernelLog() }
                    .map { logs -> { s: Local -> s.copy(logs = logs) } }
                StatusTab.ROUTES -> adminCall { admin.routes(ui.family) }.map { routes -> { s: Local -> s.copy(routes = routes) } }
                StatusTab.CONNECTIONS -> adminCall { admin.connections().sortedByDescending { it.bytes } to admin.conntrackUsage() }
                    .map { (connections, usage) -> { s: Local -> s.copy(connections = connections, usage = usage) } }
                StatusTab.SERVICES -> adminCall { admin.services() }.map { services -> { s: Local -> s.copy(services = services) } }
            }
            local.update { current ->
                // A later switch of tab, log kind or family started its own load; this result is stale.
                if (current.tab != ui.tab || current.logKind != ui.logKind || current.family != ui.family) return@update current
                result.fold({ apply -> apply(current).copy(loading = false) }, { current.copy(loading = false, failure = it.adminFailure) })
            }
        }
    }
}

val ServiceAction.label: Int
    get() = when (this) {
        ServiceAction.START -> R.string.service_start
        ServiceAction.STOP -> R.string.service_stop
        ServiceAction.RESTART -> R.string.service_restart
        ServiceAction.ENABLE -> R.string.service_enable
        ServiceAction.DISABLE -> R.string.service_disable
    }
