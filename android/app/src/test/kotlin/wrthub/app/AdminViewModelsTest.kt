package wrthub.app

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import wrthub.app.data.SiteConfig
import wrthub.app.domain.AdminFailure
import wrthub.app.domain.NodeState
import wrthub.app.domain.SiteLoading
import wrthub.app.domain.SiteSession
import wrthub.app.ui.admin.AdminNotice
import wrthub.app.ui.admin.ForwardField
import wrthub.app.ui.admin.ForwardsViewModel
import wrthub.app.ui.admin.LeaseField
import wrthub.app.ui.admin.LeasePrefill
import wrthub.app.ui.admin.LeasesViewModel
import wrthub.app.ui.admin.LogKind
import wrthub.app.ui.admin.StatusTab
import wrthub.app.ui.admin.StatusViewModel
import wrthub.app.ui.node.AdminEntries
import wrthub.app.ui.node.NodeDetailViewModel
import wrthub.core.Fixtures
import wrthub.core.admin.DhcpConfig
import wrthub.core.admin.ForwardProtocol
import wrthub.core.admin.IpFamily
import wrthub.core.admin.LogLine
import wrthub.core.admin.PortForwardInput
import wrthub.core.admin.Service
import wrthub.core.admin.ServiceAction
import wrthub.core.admin.StaticLease
import wrthub.core.admin.StaticLeaseInput
import wrthub.core.direct.PendingChangesException
import wrthub.core.direct.WriteOutcome

@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val home = SiteConfig("home", "Home", listOf(node("router", "Gateway")))

    private fun TestScope.session(): SiteSession {
        val loader = SiteLoading { site -> site.nodes.map { NodeState.Online(it, Fixtures.wiredSite.getValue(it.id)) } }
        return SiteSession(FakeSiteRepository(listOf(home)), loader, appScope())
    }

    @Test
    fun `logs show newest first and the filter matches case-insensitively`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin(logs = listOf(LogLine(1, null, 6, "first dnsmasq"), LogLine(2, null, 6, "second"), LogLine(3, null, 3, "third DNSMASQ")))
        val vm = StatusViewModel("router", session(), { admin })
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(listOf("third DNSMASQ", "second", "first dnsmasq"), vm.state.value.logs.map { it.message })
        vm.setLogFilter("dnsmasq")
        advanceUntilIdle()
        assertEquals(listOf("third DNSMASQ", "first dnsmasq"), vm.state.value.logs.map { it.message })
        vm.setLogKind(LogKind.KERNEL)
        vm.selectTab(StatusTab.ROUTES)
        vm.setFamily(IpFamily.V6)
        advanceUntilIdle()
        assertEquals("read routes V6", admin.log.last())
    }

    @Test
    fun `a service action runs and the list is reloaded, but essential services are never stopped`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin(services = listOf(Service("collectd", enabled = true, running = true), Service("rpcd", enabled = true, running = true)))
        val vm = StatusViewModel("router", session(), { admin })
        advanceUntilIdle()
        vm.selectTab(StatusTab.SERVICES)
        advanceUntilIdle()
        vm.runServiceAction("rpcd", ServiceAction.STOP)
        vm.runServiceAction("collectd", ServiceAction.STOP)
        advanceUntilIdle()
        assertEquals(listOf("rc collectd stop"), admin.log.filter { it.startsWith("rc") })
        assertTrue(vm.state.value.notice is AdminNotice.ServiceDone)
        assertEquals(2, vm.state.value.services.size)
    }

    private val nas = StaticLease("cfg05fe63", "nas", listOf("ce:11:22:00:00:01"), "192.168.1.20")

    @Test
    fun `pinning a client that already has a lease edits that lease`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin(dhcp = DhcpConfig(true, listOf(nas)))
        val vm = LeasesViewModel("router", session(), { admin }, LeasePrefill("CE:11:22:00:00:01", "192.168.1.99", "android-1"))
        advanceUntilIdle()
        val editor = assertNotNull(vm.state.value.editor)
        assertEquals("cfg05fe63", editor.section)
        assertEquals("192.168.1.20", editor.ip)
    }

    @Test
    fun `pinning a new client prefills its address and a usable name`() = runTest(main.dispatcher) {
        val vm = LeasesViewModel("router", session(), { FakeNodeAdmin() }, LeasePrefill("ce:11:22:00:00:09", "192.168.1.30", "Living Room TV"))
        advanceUntilIdle()
        val editor = assertNotNull(vm.state.value.editor)
        assertNull(editor.section)
        assertEquals(Triple("", "ce:11:22:00:00:09", "192.168.1.30"), Triple(editor.name, editor.mac, editor.ip))
    }

    @Test
    fun `an invalid address is flagged without calling the router`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin()
        val vm = LeasesViewModel("router", session(), { admin })
        advanceUntilIdle()
        vm.add()
        vm.onMac("ce:11:22:00:00:09")
        vm.onIp("192.168.1.300")
        vm.save()
        advanceUntilIdle()
        assertEquals(LeaseField.IP, vm.state.value.editor?.error)
        assertTrue(admin.log.isEmpty())
    }

    @Test
    fun `a saved lease closes the editor and reports the outcome`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin(outcome = WriteOutcome.ROLLED_BACK)
        val vm = LeasesViewModel("router", session(), { admin })
        advanceUntilIdle()
        vm.add()
        vm.onName("printer")
        vm.onMac("ce:11:22:00:00:09")
        vm.onIp("192.168.1.30")
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf("lease new ${StaticLeaseInput("printer", "ce:11:22:00:00:09", "192.168.1.30")}"), admin.log)
        assertNull(vm.state.value.editor)
        assertEquals(AdminNotice.Written(WriteOutcome.ROLLED_BACK), vm.state.value.notice)
    }

    @Test
    fun `staged changes left in the session keep the editor open`() = runTest(main.dispatcher) {
        val vm = LeasesViewModel("router", session(), { FakeNodeAdmin(writeFailure = PendingChangesException(setOf("wireless"))) })
        advanceUntilIdle()
        vm.add()
        vm.onMac("ce:11:22:00:00:09")
        vm.onIp("192.168.1.30")
        vm.save()
        advanceUntilIdle()
        assertNotNull(vm.state.value.editor)
        assertEquals(AdminNotice.Failed(AdminFailure.Pending(setOf("wireless"))), vm.state.value.notice)
    }

    @Test
    fun `a blank device port means the external port`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin()
        val vm = ForwardsViewModel("router", session(), { admin })
        advanceUntilIdle()
        vm.add()
        vm.onEditor { it.copy(protocol = ForwardProtocol.UDP, externalPort = "3074", internalIp = "192.168.1.30") }
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf("forward new ${PortForwardInput(null, ForwardProtocol.UDP, "3074", "192.168.1.30", "3074")}"), admin.log)
    }

    @Test
    fun `a bad port range is flagged`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin()
        val vm = ForwardsViewModel("router", session(), { admin })
        advanceUntilIdle()
        vm.add()
        vm.onEditor { it.copy(externalPort = "9000-8000", internalIp = "192.168.1.30") }
        vm.save()
        advanceUntilIdle()
        assertEquals(ForwardField.EXTERNAL_PORT, vm.state.value.editor?.error)
        assertTrue(admin.log.isEmpty())
    }

    @Test
    fun `online clients are offered as forward targets`() = runTest(main.dispatcher) {
        val vm = ForwardsViewModel("router", session(), { FakeNodeAdmin() })
        advanceUntilIdle()
        assertTrue(vm.state.value.targets.isNotEmpty())
        assertTrue(vm.state.value.targets.all { it.ip.isNotEmpty() })
    }

    @Test
    fun `the router offers port forwarding, and static leases only with a dhcp server`() = runTest(main.dispatcher) {
        val vm = NodeDetailViewModel("router", session()) { FakeNodeOperations() }
        advanceUntilIdle()
        assertEquals(AdminEntries(status = true, staticLeases = false, portForwards = true, wan = true, system = true), vm.state.value.admin)
    }
}
