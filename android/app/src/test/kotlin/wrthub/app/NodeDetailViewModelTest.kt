package wrthub.app

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import wrthub.app.data.SiteConfig
import wrthub.app.domain.NodeState
import wrthub.app.domain.SiteLoading
import wrthub.app.domain.SiteSession
import wrthub.app.ui.node.ExecView
import wrthub.app.ui.node.NodeDetailViewModel
import wrthub.app.ui.node.NodeNotice
import wrthub.core.Fixtures
import wrthub.core.direct.ExecResult

@OptIn(ExperimentalCoroutinesApi::class)
class NodeDetailViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val cabin = SiteConfig("cabin", "Cabin", listOf(node("router"), node("ap")))

    private fun TestScope.viewModel(operations: FakeNodeOperations, nodeId: String = "ap"): NodeDetailViewModel {
        val loader = SiteLoading { site -> site.nodes.map { NodeState.Online(it, Fixtures.meshSite.getValue(it.id)) } }
        val session = SiteSession(FakeSiteRepository(listOf(cabin)), loader, appScope())
        return NodeDetailViewModel(nodeId, session) { operations }
    }

    @Test
    fun `ping shows the command output`() = runTest(main.dispatcher) {
        val operations = FakeNodeOperations(execResult = ExecResult(0, "4 packets transmitted\n", ""))
        val vm = viewModel(operations)
        advanceUntilIdle()
        vm.onHost(" 192.0.2.1 ")
        vm.ping()
        advanceUntilIdle()
        assertEquals(listOf("ping 192.0.2.1"), operations.log)
        assertEquals(ExecView("ping 192.0.2.1", "4 packets transmitted", 0), vm.state.value.result)
        assertEquals(false, vm.state.value.running)
    }

    @Test
    fun `an invalid host is reported`() = runTest(main.dispatcher) {
        val vm = viewModel(FakeNodeOperations(failure = IllegalArgumentException("invalid host")))
        advanceUntilIdle()
        vm.traceroute()
        advanceUntilIdle()
        assertEquals(NodeNotice.InvalidHost, vm.state.value.notice)
        assertNull(vm.state.value.result)
    }

    @Test
    fun `reboot is sent to this node`() = runTest(main.dispatcher) {
        val operations = FakeNodeOperations(name = "ap")
        val vm = viewModel(operations)
        advanceUntilIdle()
        vm.reboot()
        advanceUntilIdle()
        assertEquals(listOf("reboot ap"), operations.log)
        assertEquals(NodeNotice.RebootRequested, vm.state.value.notice)
    }

    @Test
    fun `a failing reboot is reported`() = runTest(main.dispatcher) {
        val vm = viewModel(FakeNodeOperations(failure = IOException("connection reset")))
        advanceUntilIdle()
        vm.reboot()
        advanceUntilIdle()
        assertEquals(NodeNotice.Failed("connection reset"), vm.state.value.notice)
    }

    @Test
    fun `an unknown node does nothing`() = runTest(main.dispatcher) {
        val operations = FakeNodeOperations()
        val vm = viewModel(operations, nodeId = "gone")
        advanceUntilIdle()
        vm.ping()
        vm.reboot()
        advanceUntilIdle()
        assertTrue(operations.log.isEmpty())
    }
}
