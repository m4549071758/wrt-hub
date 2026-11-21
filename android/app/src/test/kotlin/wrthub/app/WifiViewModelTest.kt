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
import wrthub.app.ui.sites.WifiInputError
import wrthub.app.ui.sites.WifiNodeResult
import wrthub.app.ui.sites.WifiOutcome
import wrthub.app.ui.sites.WifiViewModel
import wrthub.core.Fixtures
import wrthub.core.direct.WifiStage

@OptIn(ExperimentalCoroutinesApi::class)
class WifiViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val cabin = SiteConfig("cabin", "Cabin", listOf(node("router", "WRC-1"), node("ap", "WRC-2")))
    private val home = SiteConfig("home", "Home", listOf(node("gw", "Gateway")))
    private val log = mutableListOf<String>()

    private fun TestScope.viewModel(siteId: String = "cabin", operations: (String) -> FakeNodeOperations): WifiViewModel {
        val loader = SiteLoading { site ->
            site.nodes.map { n -> NodeState.Online(n, if (site.id == "cabin") Fixtures.meshSite.getValue(n.id) else Fixtures.wiredSite.getValue("router")) }
        }
        val session = SiteSession(FakeSiteRepository(listOf(home, cabin), selected = "home"), loader, appScope())
        return WifiViewModel(siteId, session) { node -> operations(node.id) }
    }

    private fun WifiViewModel.enter(ssid: String = "New-SSID", key: String = "correct horse") {
        onSsid(ssid)
        onKey(key)
    }

    @Test
    fun `opening selects the site and shows its current AP SSIDs`() = runTest(main.dispatcher) {
        val vm = viewModel { FakeNodeOperations(it, log) }
        advanceUntilIdle()
        assertEquals(cabin, vm.state.value.site)
        assertEquals(1, vm.state.value.currentSsids.size)
    }

    @Test
    fun `every node is staged before any node applies`() = runTest(main.dispatcher) {
        val vm = viewModel { FakeNodeOperations(it, log) }
        advanceUntilIdle()
        vm.enter()
        vm.apply()
        advanceUntilIdle()
        assertEquals(listOf("stage router", "stage ap", "apply router", "apply ap"), log)
        assertEquals(
            listOf(WifiNodeResult("WRC-1", WifiOutcome.Changed(1)), WifiNodeResult("WRC-2", WifiOutcome.Changed(1))),
            vm.state.value.results,
        )
        assertEquals("", vm.state.value.key)
    }

    @Test
    fun `a node without an AP is left alone`() = runTest(main.dispatcher) {
        val vm = viewModel { FakeNodeOperations(it, log, wifiStage = if (it == "router") WifiStage(0, 0) else WifiStage(2, 2)) }
        advanceUntilIdle()
        vm.enter()
        vm.apply()
        advanceUntilIdle()
        assertEquals(listOf("stage router", "stage ap", "apply ap"), log)
        assertEquals(WifiOutcome.NoAccessPoint, vm.state.value.results!!.first().outcome)
    }

    @Test
    fun `a node that already has the requested SSID and key is not applied`() = runTest(main.dispatcher) {
        val vm = viewModel { FakeNodeOperations(it, log, wifiStage = if (it == "router") WifiStage(2, 0) else WifiStage(2, 2)) }
        advanceUntilIdle()
        vm.enter()
        vm.apply()
        advanceUntilIdle()
        assertEquals(listOf("stage router", "stage ap", "apply ap"), log)
        assertEquals(
            listOf(WifiNodeResult("WRC-1", WifiOutcome.Unchanged), WifiNodeResult("WRC-2", WifiOutcome.Changed(2))),
            vm.state.value.results,
        )
    }

    @Test
    fun `if one node fails to stage nothing is applied and staged changes are discarded`() = runTest(main.dispatcher) {
        val vm = viewModel { FakeNodeOperations(it, log, failure = if (it == "ap") IOException("timeout") else null) }
        advanceUntilIdle()
        vm.enter()
        vm.apply()
        advanceUntilIdle()
        assertEquals(listOf("stage router", "discard router"), log)
        assertEquals(
            listOf(WifiNodeResult("WRC-1", WifiOutcome.NotApplied), WifiNodeResult("WRC-2", WifiOutcome.Failed("timeout"))),
            vm.state.value.results,
        )
    }

    @Test
    fun `invalid input never reaches a router`() = runTest(main.dispatcher) {
        val vm = viewModel { FakeNodeOperations(it, log) }
        advanceUntilIdle()
        vm.enter(ssid = "")
        vm.apply()
        advanceUntilIdle()
        assertEquals(WifiInputError.SSID, vm.state.value.inputError)
        vm.enter(key = "short")
        vm.apply()
        advanceUntilIdle()
        assertEquals(WifiInputError.KEY, vm.state.value.inputError)
        assertTrue(log.isEmpty())
    }
}
