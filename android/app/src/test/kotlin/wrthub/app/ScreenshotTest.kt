package wrthub.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import wrthub.app.data.SiteConfig
import wrthub.app.domain.NodeState
import wrthub.app.domain.Rate
import wrthub.app.domain.siteTopology
import wrthub.app.ui.clients.ClientDetail
import wrthub.app.ui.clients.ClientsScreen
import wrthub.app.ui.clients.ClientsUiState
import wrthub.app.ui.home.HomeScreen
import wrthub.app.ui.mesh.MeshEdge
import wrthub.app.ui.mesh.MeshNode
import wrthub.app.ui.mesh.MeshScreen
import wrthub.app.ui.mesh.MeshSelection
import wrthub.app.ui.mesh.MeshUiState
import wrthub.app.ui.home.HomeUiState
import wrthub.app.ui.sites.AddNodeActions
import wrthub.app.ui.sites.AddNodeError
import wrthub.app.ui.sites.AddNodeScreen
import wrthub.app.ui.sites.AddNodeStep
import wrthub.app.ui.sites.AddNodeUiState
import wrthub.app.ui.sites.SitesScreen
import wrthub.app.ui.theme.WrtHubTheme
import wrthub.core.Fixtures
import wrthub.core.topology.Presence
import wrthub.app.ui.node.ExecView
import wrthub.app.ui.node.NodeDetailActions
import wrthub.app.ui.node.NodeDetailScreen
import wrthub.app.ui.node.NodeDetailUiState
import wrthub.app.ui.sites.WifiActions
import wrthub.app.ui.sites.WifiNodeResult
import wrthub.app.ui.sites.WifiOutcome
import wrthub.app.ui.sites.WifiScreen
import wrthub.app.ui.sites.WifiUiState
import wrthub.app.ui.admin.ForwardsActions
import wrthub.app.ui.admin.ForwardsScreen
import wrthub.app.ui.admin.ForwardsUiState
import wrthub.app.ui.admin.LeasesActions
import wrthub.app.ui.admin.LeasesScreen
import wrthub.app.ui.admin.LeasesUiState
import wrthub.app.ui.admin.StatusActions
import wrthub.app.ui.admin.StatusScreen
import wrthub.app.ui.admin.StatusTab
import wrthub.app.ui.admin.StatusUiState
import wrthub.app.ui.node.AdminEntries
import wrthub.core.admin.ForwardProtocol
import wrthub.core.admin.LogLine
import wrthub.core.admin.PortForward
import wrthub.core.admin.Service
import wrthub.core.admin.StaticLease

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val cabin = SiteConfig("cabin", "Cabin", listOf(node("router", "Router"), node("ap", "Living AP")))
    private val home = SiteConfig("home", "Home", listOf(node("home-router", "Gateway")))
    private val online = listOf(
        NodeState.Online(cabin.nodes[0], Fixtures.meshSite.getValue("router")),
        NodeState.Online(cabin.nodes[1], Fixtures.meshSite.getValue("ap")),
    )
    private val rates = List(40) { Rate(4.0e7 + 2.0e7 * sin(it / 4.0), 8.0e6 + 4.0e6 * cos(it / 5.0)) }
    private val noActions = AddNodeActions({}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { WrtHubTheme(darkTheme = dark, dynamicColor = false, content = content) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun homeState(nodes: List<NodeState>) = HomeUiState(sites = listOf(home, cabin), site = cabin, nodes = nodes, rates = rates)

    @Test
    fun homeLight() = capture("home_light") { HomeScreen(homeState(online), {}, {}, {}) }

    @Test
    fun homeDark() = capture("home_dark", dark = true) { HomeScreen(homeState(online), {}, {}, {}) }

    @Test
    fun homeUnreachableAp() = capture("home_unreachable_ap") {
        HomeScreen(homeState(listOf(online[0], NodeState.Unreachable(cabin.nodes[1], "timeout"))), {}, {}, {})
    }

    @Test
    fun homeEmpty() = capture("home_empty") { HomeScreen(HomeUiState(), {}, {}, {}) }

    @Test
    fun addSiteWithError() = capture("add_site_error") {
        AddNodeScreen(
            AddNodeUiState(needsSiteName = true, siteName = "Cabin", label = "Router", url = "192.168.1.1", error = AddNodeError.LOGIN_FAILED),
            noActions,
        )
    }

    @Test
    fun certificateDialog() = capture("add_site_certificate") {
        AddNodeScreen(
            AddNodeUiState(needsSiteName = true, url = "https://192.168.1.1", step = AddNodeStep.ConfirmCertificate("AB:CD:".repeat(15) + "EF")),
            noActions,
        )
    }

    @Test
    fun sites() = capture("sites") { SitesScreen(listOf(home, cabin), {}, {}, {}, { _, _ -> }) }

    @Test
    fun nodeDetail() = capture("node_detail") {
        NodeDetailScreen(
            NodeDetailUiState(
                node = online[0],
                host = "1.1.1.1",
                result = ExecView("ping 1.1.1.1", "PING 1.1.1.1 (1.1.1.1): 56 data bytes\n64 bytes from 1.1.1.1: seq=0 ttl=57 time=38.211 ms\n\n--- 1.1.1.1 ping statistics ---\n4 packets transmitted, 4 packets received, 0% packet loss", 0),
                admin = AdminEntries(status = true, staticLeases = true, portForwards = true),
            ),
            NodeDetailActions({}, {}, {}, {}, {}, {}),
        )
    }

    @Test
    fun wifi() = capture("wifi") {
        WifiScreen(
            WifiUiState(
                site = cabin,
                currentSsids = listOf("ssid-2"),
                ssid = "New-SSID",
                results = listOf(WifiNodeResult("Router", WifiOutcome.Changed(2)), WifiNodeResult("Living AP", WifiOutcome.NotApplied)),
            ),
            WifiActions({}, {}, { true }, {}, {}),
        )
    }

    private val topology = siteTopology(online)
    private val labels = mapOf("router" to "Router", "ap" to "Living AP")

    @Test
    fun clients() = capture("clients") {
        ClientsScreen(ClientsUiState(site = cabin, nodeLabels = labels, clients = topology.clients), {}, {}, { _, _ -> }, {})
    }

    @Test
    fun clientDetail() = capture("client_detail") {
        val client = topology.clients.first { it.sightings.size == 2 }
        ClientDetail(client, labels, busy = false) { _, _ -> }
    }

    private val meshState = MeshUiState(
        site = cabin,
        nodes = listOf(
            MeshNode("router", "Router", true, topology.clients.filter { it.attachedTo == "router" }),
            MeshNode("ap", "Living AP", true, topology.clients.filter { it.attachedTo == "ap" }),
        ),
        edges = listOf(MeshEdge("ap", "router", 1_003_176.0, 856_400.0, -61)),
        routerId = "router",
    )

    @Test
    fun meshLight() = capture("mesh_light") { MeshScreen(meshState) {} }

    @Test
    fun meshDarkEdgeSelected() = capture("mesh_dark_edge", dark = true) {
        MeshScreen(meshState.copy(selection = MeshSelection.Edge("ap", "router"))) {}
    }

    @Test
    fun meshWiredSite() = capture("mesh_wired") {
        val nodes = listOf(NodeState.Online(home.nodes[0], Fixtures.wiredSite.getValue("router")))
        val wired = siteTopology(nodes)
        MeshScreen(
            MeshUiState(
                site = home,
                nodes = listOf(MeshNode("home-router", "Gateway", true, wired.clients.filter { it.presence == Presence.ONLINE_UNKNOWN_LINK })),
                routerId = "home-router",
                departed = wired.clients.count { it.presence == Presence.LEASE_ONLY },
            ),
        ) {}
    }

    @Test
    fun statusLogs() = capture("status_logs") {
        val lines = listOf(
            LogLine(1_795_300_000_000, null, 6, "daemon.info dnsmasq-dhcp[1]: DHCPACK(br-lan) 192.168.1.30 ce:11:22:00:00:09 printer"),
            LogLine(1_795_300_005_000, null, 4, "daemon.warn odhcpd[2]: No default route present, overriding ra_lifetime!"),
            LogLine(1_795_300_010_000, null, 3, "daemon.err uhttpd[3]: luci: accepted login on / for root from 192.168.1.10"),
        )
        StatusScreen(StatusUiState(title = "Router", logs = lines.asReversed()), statusActions)
    }

    @Test
    fun statusServices() = capture("status_services", dark = true) {
        val services = listOf(Service("boot", true, null), Service("collectd", false, false), Service("dnsmasq", true, true), Service("rpcd", true, true))
        StatusScreen(StatusUiState(title = "Router", tab = StatusTab.SERVICES, services = services), statusActions)
    }

    @Test
    fun leases() = capture("leases") {
        val leases = listOf(
            StaticLease("cfg05fe63", "nas", listOf("ce:11:22:00:00:01"), "192.168.1.20"),
            StaticLease("cfg06fe63", "tv", listOf("ce:11:22:00:00:02", "ce:11:22:00:00:03"), "192.168.1.21"),
        )
        LeasesScreen(LeasesUiState(title = "Router", servesDhcp = true, leases = leases), LeasesActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}))
    }

    @Test
    fun forwards() = capture("forwards") {
        val forwards = listOf(
            PortForward("a", "web", ForwardProtocol.BOTH, "8443", "192.168.1.20", "443", enabled = true, editable = true),
            PortForward("b", "game", ForwardProtocol.UDP, "3074", "192.168.1.30", "3074", enabled = false, editable = true),
            PortForward("c", "ssh", ForwardProtocol.TCP, "2222", "192.168.1.20", "22", enabled = true, editable = false),
        )
        ForwardsScreen(ForwardsUiState(title = "Router", forwards = forwards), ForwardsActions({}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}))
    }

    private val statusActions = StatusActions({}, {}, {}, {}, { _, _ -> }, {}, {}, {})
}
