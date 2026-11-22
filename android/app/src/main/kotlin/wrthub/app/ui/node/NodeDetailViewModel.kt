package wrthub.app.ui.node

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import wrthub.app.data.NodeConfig
import wrthub.app.domain.NodeOperations
import wrthub.app.domain.NodeState
import wrthub.app.domain.SiteSession
import wrthub.core.direct.ExecResult
import wrthub.core.ubus.UbusException
import wrthub.core.admin.parseDhcpConfig
import wrthub.core.model.NodeSummary
import wrthub.core.model.summary
import wrthub.core.model.wanDevice

sealed interface NodeNotice {
    data object InvalidHost : NodeNotice

    data object RebootRequested : NodeNotice

    data class Failed(val reason: String) : NodeNotice
}

data class ExecView(val title: String, val output: String, val exitCode: Int)

/** Which LuCI-replacement pages the node offers; status needs the node to answer. */
data class AdminEntries(
    val status: Boolean = false,
    val staticLeases: Boolean = false,
    val portForwards: Boolean = false,
    val wireless: Boolean = false,
    val wan: Boolean = false,
    val system: Boolean = false,
    val packages: Boolean = false,
)

data class NodeDetailUiState(
    val node: NodeState? = null,
    val host: String = NodeDetailViewModel.DEFAULT_HOST,
    val running: Boolean = false,
    val result: ExecView? = null,
    val notice: NodeNotice? = null,
    val admin: AdminEntries = AdminEntries(),
    /** 1-minute load of each snapshot that arrived while the page was open, oldest first. */
    val loadHistory: List<Double> = emptyList(),
    /** Used fraction of the memory (0..1), sampled like [loadHistory]. */
    val memoryHistory: List<Double> = emptyList(),
)

/** The last [NodeDetailViewModel.HISTORY_SIZE] samples of load and memory use. */
private data class Samples(val load: List<Double> = emptyList(), val memory: List<Double> = emptyList()) {
    fun plus(summary: NodeSummary): Samples {
        val total = summary.memoryTotalBytes
        val memory = if (total > 0) memory + (total - summary.memoryAvailableBytes).toDouble() / total else memory
        return Samples(
            (load + summary.load1).takeLast(NodeDetailViewModel.HISTORY_SIZE),
            memory.takeLast(NodeDetailViewModel.HISTORY_SIZE),
        )
    }
}

class NodeDetailViewModel(
    private val nodeId: String,
    private val session: SiteSession,
    private val refreshIntervalMs: Long = 5_000,
    private val operationsFor: (NodeConfig) -> NodeOperations,
) : ViewModel() {
    private data class Local(
        val host: String = DEFAULT_HOST,
        val running: Boolean = false,
        val result: ExecView? = null,
        val notice: NodeNotice? = null,
    )

    private val local = MutableStateFlow(Local())
    private var liveJob: Job? = null

    private val samples = session.state
        .map { site -> site.nodes.firstOrNull { it.config.id == nodeId } }
        .filterIsInstance<NodeState.Online>()
        .map { it.snapshot }
        .distinctUntilChanged { old, new -> old === new }
        .runningFold(Samples()) { acc, snapshot -> acc.plus(snapshot.summary()) }

    val state: StateFlow<NodeDetailUiState> = combine(session.state, local, samples) { site, ui, history ->
        val node = site.nodes.firstOrNull { it.config.id == nodeId }
        NodeDetailUiState(node, ui.host, ui.running, ui.result, ui.notice, adminEntries(node), history.load, history.memory)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NodeDetailUiState())

    fun onHost(value: String) = local.update { it.copy(host = value) }

    fun ping() = runDiagnostic("ping") { operations, host -> operations.ping(host) }

    fun traceroute() = runDiagnostic("traceroute") { operations, host -> operations.traceroute(host) }

    fun reboot() {
        val node = nodeConfig() ?: return
        local.update { it.copy(running = true, notice = null) }
        viewModelScope.launch {
            val notice = failureOf { operationsFor(node).reboot() } ?: NodeNotice.RebootRequested
            local.update { it.copy(running = false, notice = notice) }
        }
    }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    /** While [active], reloads the site every [refreshIntervalMs] so the load and memory lines advance. */
    fun setLive(active: Boolean) {
        liveJob?.cancel()
        liveJob = if (active) session.keepFresh(refreshIntervalMs) else null
    }

    override fun onCleared() {
        liveJob?.cancel()
    }

    private fun nodeConfig(): NodeConfig? = session.state.value.nodes.firstOrNull { it.config.id == nodeId }?.config

    private fun runDiagnostic(name: String, call: suspend (NodeOperations, String) -> ExecResult) {
        val node = nodeConfig() ?: return
        val host = local.value.host.trim()
        local.update { it.copy(running = true, result = null, notice = null) }
        viewModelScope.launch {
            var result: ExecView? = null
            val notice = failureOf {
                val r = call(operationsFor(node), host)
                result = ExecView("$name $host", (r.stdout + r.stderr).trimEnd(), r.exitCode)
            }
            local.update { it.copy(running = false, result = result, notice = notice) }
        }
    }

    /** Runs [block] and maps the failures a router call can raise to a notice; null on success. */
    private suspend fun failureOf(block: suspend () -> Unit): NodeNotice? =
        try {
            block()
            null
        } catch (e: IllegalArgumentException) {
            NodeNotice.InvalidHost
        } catch (e: IOException) {
            NodeNotice.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: UbusException) {
            NodeNotice.Failed(e.message ?: "ubus status ${e.code}")
        }

    companion object {
        const val DEFAULT_HOST = "1.1.1.1"
        const val HISTORY_SIZE = 60
    }
}

/** Static leases only where a DHCP server runs, port forwards only on the node with the WAN. */
private fun adminEntries(node: NodeState?): AdminEntries {
    val snapshot = (node as? NodeState.Online)?.snapshot ?: return AdminEntries()
    val router = snapshot.wanDevice() != null
    return AdminEntries(
        status = true,
        staticLeases = snapshot.dhcpConfig?.let(::parseDhcpConfig)?.servesDhcp == true,
        portForwards = router,
        wireless = snapshot.wireless.isNotEmpty(),
        wan = router,
        system = true,
        packages = true,
    )
}
