package wrthub.app

import java.io.IOException
import kotlin.test.assertEquals
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
import wrthub.app.ui.clients.ClientFilter
import wrthub.app.ui.clients.ClientNotice
import wrthub.app.ui.clients.ClientsViewModel
import wrthub.core.Fixtures
import wrthub.core.direct.WriteOutcome

@OptIn(ExperimentalCoroutinesApi::class)
class ClientsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val cabin = SiteConfig("cabin", "Cabin", listOf(node("router", "WRC-1"), node("ap", "WRC-2")))
    private var loads = 0
    private val targets = mutableListOf<String>()

    private fun TestScope.viewModel(blocking: FakeNodeOperations, routerReachable: Boolean = true): ClientsViewModel {
        val loader = SiteLoading { site ->
            loads++
            site.nodes.map { n ->
                if (n.id == "router" && !routerReachable) NodeState.Unreachable(n, "timeout")
                else NodeState.Online(n, Fixtures.meshSite.getValue(n.id))
            }
        }
        val session = SiteSession(FakeSiteRepository(listOf(cabin)), loader, appScope())
        return ClientsViewModel(session) { node -> targets += node.id; blocking }
    }

    @Test
    fun `clients are listed with node labels and can be filtered`() = runTest(main.dispatcher) {
        val vm = viewModel(FakeNodeOperations())
        advanceUntilIdle()
        assertEquals(5, vm.state.value.clients.size)
        assertEquals(mapOf("router" to "WRC-1", "ap" to "WRC-2"), vm.state.value.nodeLabels)
        vm.setFilter(ClientFilter.OTHER)
        advanceUntilIdle()
        assertTrue(vm.state.value.clients.isEmpty())
        vm.setFilter(ClientFilter.WIRELESS)
        advanceUntilIdle()
        assertEquals(5, vm.state.value.clients.size)
    }

    @Test
    fun `blocking goes to the router and reloads the site`() = runTest(main.dispatcher) {
        val blocking = FakeNodeOperations()
        val vm = viewModel(blocking)
        advanceUntilIdle()
        val mac = vm.state.value.clients.first().mac
        vm.setBlocked(mac, true)
        advanceUntilIdle()
        assertEquals(listOf("router"), targets)
        assertEquals(listOf("block $mac"), blocking.log)
        assertEquals(2, loads)
        assertEquals(ClientNotice.Done(mac, blocked = true, outcome = WriteOutcome.APPLIED), vm.state.value.notice)
        assertTrue(vm.state.value.busy.isEmpty())
    }

    @Test
    fun `a failing block is reported`() = runTest(main.dispatcher) {
        val vm = viewModel(FakeNodeOperations(failure = IOException("reset")))
        advanceUntilIdle()
        val mac = vm.state.value.clients.first().mac
        vm.setBlocked(mac, true)
        advanceUntilIdle()
        assertEquals(ClientNotice.Failed(mac, "reset"), vm.state.value.notice)
    }

    @Test
    fun `without a reachable router nothing is sent`() = runTest(main.dispatcher) {
        val blocking = FakeNodeOperations()
        val vm = viewModel(blocking, routerReachable = false)
        advanceUntilIdle()
        val mac = vm.state.value.clients.first().mac
        vm.setBlocked(mac, true)
        advanceUntilIdle()
        assertEquals(ClientNotice.NoRouter, vm.state.value.notice)
        assertTrue(blocking.log.isEmpty())
    }
}
