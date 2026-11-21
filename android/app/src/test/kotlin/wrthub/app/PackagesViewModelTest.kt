package wrthub.app

import kotlin.test.assertEquals
import kotlin.test.assertNull
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
import wrthub.app.ui.admin.PackageAction
import wrthub.app.ui.admin.PackageTab
import wrthub.app.ui.admin.PackagesViewModel
import wrthub.core.Fixtures
import wrthub.core.admin.PackageInfo
import wrthub.core.admin.PackageResult

@OptIn(ExperimentalCoroutinesApi::class)
class PackagesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val home = SiteConfig("home", "Home", listOf(node("router")))

    private fun TestScope.session(): SiteSession {
        val loader = SiteLoading { site -> site.nodes.map { NodeState.Online(it, Fixtures.wiredSite.getValue(it.id)) } }
        return SiteSession(FakeSiteRepository(listOf(home)), loader, appScope())
    }

    private val tcpdump = PackageInfo("tcpdump-mini", "4.99.5-r1", "Network monitoring", 385024)
    private val sqm = PackageInfo("luci-app-sqm", "1.0", "Smart queue management UI", 30000)

    @Test
    fun `the available list loads only when its tab opens and marks installed packages`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin(installed = listOf(tcpdump), available = listOf(sqm, tcpdump))
        val vm = PackagesViewModel("router", session(), { admin })
        advanceUntilIdle()
        assertEquals(listOf("tcpdump-mini"), vm.state.value.packages?.map { it.name })
        assertEquals(0, admin.availableCalls)
        vm.selectTab(PackageTab.AVAILABLE)
        advanceUntilIdle()
        assertEquals(1, admin.availableCalls)
        assertEquals(setOf("tcpdump-mini"), vm.state.value.installedNames)
        vm.setFilter("queue")
        advanceUntilIdle()
        assertEquals(listOf("luci-app-sqm"), vm.state.value.packages?.map { it.name })
    }

    @Test
    fun `apk's refusal is shown with its output`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin(installed = listOf(tcpdump), packageResult = PackageResult(1, "", "ERROR: tcpdump-mini is required by foo"))
        val vm = PackagesViewModel("router", session(), { admin })
        advanceUntilIdle()
        vm.remove("tcpdump-mini")
        advanceUntilIdle()
        val outcome = vm.state.value.outcome!!
        assertEquals(PackageAction.REMOVE, outcome.action)
        assertEquals("ERROR: tcpdump-mini is required by foo", outcome.result.stderr)
        assertNull(vm.state.value.notice)
        assertEquals(listOf("remove tcpdump-mini"), admin.log)
    }
}
