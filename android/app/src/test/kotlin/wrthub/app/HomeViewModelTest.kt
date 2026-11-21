package wrthub.app

import java.io.IOException
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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
import wrthub.app.domain.SiteSession
import wrthub.app.ui.home.HomeViewModel
import wrthub.core.Fixtures
import wrthub.core.model.DeviceCounters

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val cabin = SiteConfig("cabin", "Cabin", listOf(node("router"), node("ap")))

    private fun TestScope.viewModel(counters: CounterReading): HomeViewModel {
        val loader = SiteLoading { site -> site.nodes.map { NodeState.Online(it, Fixtures.meshSite.getValue(it.id)) } }
        val session = SiteSession(FakeSiteRepository(listOf(cabin)), loader, appScope())
        return HomeViewModel(session, counters) { testScheduler.currentTime }
    }

    @Test
    fun `state mirrors the shared session`() = runTest(main.dispatcher) {
        val vm = viewModel { emptyMap() }
        advanceUntilIdle()
        assertEquals(cabin, vm.state.value.site)
        assertEquals(listOf("router", "ap"), vm.state.value.nodes.map { it.config.id })
    }

    @Test
    fun `realtime polls the router's WAN counters until stopped`() = runTest(main.dispatcher) {
        var reads = 0
        val vm = viewModel { reads++; mapOf("wan" to DeviceCounters(1000L * reads, 500L * reads)) }
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
        val vm = viewModel { throw IOException("reset") }
        advanceUntilIdle()
        vm.setRealtime(true)
        runCurrent()
        assertEquals("reset", vm.state.value.realtimeError)
        vm.setRealtime(false)
    }
}
