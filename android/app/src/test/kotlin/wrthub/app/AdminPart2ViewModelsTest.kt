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
import wrthub.app.domain.NodeState
import wrthub.app.domain.SiteLoading
import wrthub.app.domain.SiteSession
import wrthub.app.ui.admin.LeasesViewModel
import wrthub.app.ui.admin.RangeField
import wrthub.app.ui.admin.SystemField
import wrthub.app.ui.admin.SystemViewModel
import wrthub.app.ui.admin.WanField
import wrthub.app.ui.admin.WanViewModel
import wrthub.app.ui.admin.WirelessViewModel
import wrthub.app.ui.node.AdminEntries
import wrthub.app.ui.node.NodeDetailViewModel
import wrthub.core.Fixtures
import wrthub.core.admin.ChannelOption
import wrthub.core.admin.DhcpRange
import wrthub.core.admin.DhcpRangeInput
import wrthub.core.admin.Radio
import wrthub.core.admin.RadioCapabilities
import wrthub.core.admin.RadioInput
import wrthub.core.admin.SystemInput
import wrthub.core.admin.WanConfig
import wrthub.core.admin.WanInput
import wrthub.core.admin.WanProto
import wrthub.core.admin.WirelessConfig

@OptIn(ExperimentalCoroutinesApi::class)
class AdminPart2ViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val cabin = SiteConfig("cabin", "Cabin", listOf(node("router"), node("ap")))

    private fun TestScope.session(): SiteSession {
        val loader = SiteLoading { site -> site.nodes.map { NodeState.Online(it, Fixtures.meshSite.getValue(it.id)) } }
        return SiteSession(FakeSiteRepository(listOf(cabin)), loader, appScope())
    }

    private val radio = Radio("radio1", "5g", "36", "HE80", null, carriesMesh = false)
    private val caps = RadioCapabilities(
        channels = listOf(ChannelOption(36, 5180, emptySet()), ChannelOption(140, 5700, setOf("no_80mhz", "no_160mhz"))),
        htmodes = listOf("HE20", "HE40", "HE80"),
        txpowers = listOf(10, 20),
    )

    @Test
    fun `a channel that cannot carry the current width falls back to the widest it can`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin(wirelessConfig = WirelessConfig(listOf(radio), emptyList()), capabilities = caps)
        val vm = WirelessViewModel("ap", session(), { admin })
        advanceUntilIdle()
        vm.edit(radio)
        vm.onChannel("140")
        vm.onTxpower(10)
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf("radio radio1 ${RadioInput("140", "HE40", 10)}"), admin.log)
        assertNull(vm.state.value.editor)
    }

    @Test
    fun `a pool beyond the subnet flags the count, a bad lease time flags the time`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin(ranges = listOf(DhcpRange("lan", "lan", 100, 150, "12h", "192.168.1.1/24")))
        val vm = LeasesViewModel("router", session(), { admin })
        advanceUntilIdle()
        vm.editRange(admin.ranges.single())
        vm.onRange { it.copy(limit = "200") }
        vm.saveRange()
        advanceUntilIdle()
        assertEquals(RangeField.LIMIT, vm.state.value.rangeEditor?.error)
        vm.onRange { it.copy(limit = "50", leasetime = "soon") }
        vm.saveRange()
        advanceUntilIdle()
        assertEquals(RangeField.LEASETIME, vm.state.value.rangeEditor?.error)
        vm.onRange { it.copy(leasetime = "1d") }
        vm.saveRange()
        advanceUntilIdle()
        assertEquals(listOf("range lan ${DhcpRangeInput(100, 50, "1d")}"), admin.log)
    }

    private fun wan(proto: WanProto?, hasPassword: Boolean = false) =
        WanConfig(proto?.name?.lowercase() ?: "ipip6", proto, null, null, null, emptyList(), "user", hasPassword)

    @Test
    fun `pppoe needs a password unless one is stored, and a blank one keeps it`() = runTest(main.dispatcher) {
        val fresh = FakeNodeAdmin(wanConfig = wan(WanProto.DHCP))
        val vm = WanViewModel("router", session(), { fresh })
        advanceUntilIdle()
        vm.onForm { it.copy(proto = WanProto.PPPOE, username = "user") }
        vm.save()
        advanceUntilIdle()
        assertEquals(WanField.PASSWORD, vm.state.value.form.error)
        assertTrue(fresh.log.isEmpty())

        val stored = FakeNodeAdmin(wanConfig = wan(WanProto.PPPOE, hasPassword = true))
        val vm2 = WanViewModel("router", session(), { stored })
        advanceUntilIdle()
        vm2.save()
        advanceUntilIdle()
        assertEquals(listOf("wan ${WanInput.Pppoe("user", null)}"), stored.log)
    }

    @Test
    fun `static wan lists dns servers separated by spaces or commas`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin(wanConfig = wan(WanProto.DHCP))
        val vm = WanViewModel("router", session(), { admin })
        advanceUntilIdle()
        vm.onForm { it.copy(proto = WanProto.STATIC, ipaddr = "203.0.113.2", gateway = "203.0.113.1", dns = "192.0.2.53, 192.0.2.54") }
        vm.save()
        advanceUntilIdle()
        assertEquals(
            listOf("wan ${WanInput.Static("203.0.113.2", "255.255.255.0", "203.0.113.1", listOf("192.0.2.53", "192.0.2.54"))}"),
            admin.log,
        )
    }

    @Test
    fun `system settings check the host name and zone before writing`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin()
        val vm = SystemViewModel("router", session(), { admin })
        advanceUntilIdle()
        vm.onForm { it.copy(hostname = "bad name") }
        vm.saveSystem()
        advanceUntilIdle()
        assertEquals(SystemField.HOSTNAME, vm.state.value.form.error)
        vm.onForm { it.copy(hostname = "gw", zonename = "Asia/Tokyo", ntpServers = "ntp.example\n\n time.example ") }
        vm.saveSystem()
        advanceUntilIdle()
        assertEquals(listOf("system ${SystemInput("gw", "Asia/Tokyo", true, listOf("ntp.example", "time.example"))}"), admin.log)
    }

    @Test
    fun `the crontab is saved as edited`() = runTest(main.dispatcher) {
        val admin = FakeNodeAdmin(cron = "")
        val vm = SystemViewModel("router", session(), { admin })
        advanceUntilIdle()
        vm.onCron("0 4 * * * /sbin/reboot")
        vm.saveCron()
        advanceUntilIdle()
        assertEquals(listOf("cron 0 4 * * * /sbin/reboot"), admin.log)
        assertNotNull(vm.state.value.notice)
    }

    @Test
    fun `an access point offers wi-fi and system but not wan or port forwarding`() = runTest(main.dispatcher) {
        val vm = NodeDetailViewModel("ap", session()) { FakeNodeOperations() }
        advanceUntilIdle()
        assertEquals(AdminEntries(status = true, wireless = true, system = true), vm.state.value.admin)
    }
}
