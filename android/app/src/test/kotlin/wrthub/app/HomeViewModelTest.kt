package wrthub.app

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import wrthub.app.data.SiteConfig
import wrthub.app.domain.CounterReading
import wrthub.app.domain.NodeState
import wrthub.app.domain.Rate
import wrthub.app.domain.SiteLoading
import wrthub.app.ui.home.HomeViewModel
import wrthub.core.Fixtures
import wrthub.core.model.DeviceCounters

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val cabin = SiteConfig("cabin", "Cabin", listOf(node("router"), node("ap")))
    private val home = SiteConfig("home", "Home", listOf(node("router")))

    private fun loader(unreachable: Set<String> = emptySet()) = SiteLoading { site ->
        val snapshots = if (site.id == "cabin") Fixtures.meshSite else Fixtures.wiredSite
        site.nodes.map { n ->
            if (n.id in unreachable) NodeState.Unreachable(n, "timeout") else NodeState.Online(n, snapshots.getValue(n.id))
        }
    }

    private fun viewModel(
        repository: FakeSiteRepository = FakeSiteRepository(listOf(home, cabin), "cabin"),
        loader: SiteLoading = loader(),
        counters: CounterReading = CounterReading { emptyMap() },
    ) = HomeViewModel(repository, loader, counters) { main.dispatcher.scheduler.currentTime }

    @Test
    fun `loads the nodes of the selected site`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(cabin, vm.state.value.site)
        assertEquals(listOf("router", "ap"), vm.state.value.nodes.map { it.config.id })
        assertEquals(false, vm.state.value.loading)
    }

    @Test
    fun `unreachable node is kept next to the reachable ones`() = runTest(main.dispatcher) {
        val vm = viewModel(loader = loader(unreachable = setOf("ap")))
        advanceUntilIdle()
        assertIs<NodeState.Online>(vm.state.value.nodes[0])
        assertEquals(NodeState.Unreachable(cabin.nodes[1], "timeout"), vm.state.value.nodes[1])
    }

    @Test
    fun `switching site reloads with that site's nodes`() = runTest(main.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.select("home")
        advanceUntilIdle()
        assertEquals(home, vm.state.value.site)
        assertEquals(listOf("router"), vm.state.value.nodes.map { it.config.id })
    }

    @Test
    fun `realtime polls the router's WAN counters until stopped`() = runTest(main.dispatcher) {
        var reads = 0
        val vm = viewModel(counters = CounterReading { reads++; mapOf("wan" to DeviceCounters(1000L * reads, 500L * reads)) })
        advanceUntilIdle()
        vm.setRealtime(true)
        runCurrent()
        advanceTimeBy(2_001)
        assertEquals(3, reads)
        assertEquals(listOf(Rate(8000.0, 4000.0), Rate(8000.0, 4000.0)), vm.state.value.rates)
        vm.setRealtime(false)
        advanceTimeBy(5_000)
        assertEquals(3, reads)
    }

    @Test
    fun `realtime failure is reported`() = runTest(main.dispatcher) {
        val vm = viewModel(counters = CounterReading { throw IOException("reset") })
        advanceUntilIdle()
        vm.setRealtime(true)
        runCurrent()
        assertEquals("reset", vm.state.value.realtimeError)
        vm.setRealtime(false)
    }
}
