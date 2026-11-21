package wrthub.app.ui.node

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import wrthub.app.data.NodeConfig
import wrthub.app.domain.NodeOperations
import wrthub.app.domain.NodeState
import wrthub.app.domain.SiteSession
import wrthub.core.direct.ExecResult
import wrthub.core.ubus.UbusException

sealed interface NodeNotice {
    data object InvalidHost : NodeNotice

    data object RebootRequested : NodeNotice

    data class Failed(val reason: String) : NodeNotice
}

data class ExecView(val title: String, val output: String, val exitCode: Int)

data class NodeDetailUiState(
    val node: NodeState? = null,
    val host: String = NodeDetailViewModel.DEFAULT_HOST,
    val running: Boolean = false,
    val result: ExecView? = null,
    val notice: NodeNotice? = null,
)

class NodeDetailViewModel(
    private val nodeId: String,
    private val session: SiteSession,
    private val operationsFor: (NodeConfig) -> NodeOperations,
) : ViewModel() {
    private data class Local(
        val host: String = DEFAULT_HOST,
        val running: Boolean = false,
        val result: ExecView? = null,
        val notice: NodeNotice? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<NodeDetailUiState> = combine(session.state, local) { site, ui ->
        NodeDetailUiState(site.nodes.firstOrNull { it.config.id == nodeId }, ui.host, ui.running, ui.result, ui.notice)
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
    }
}
