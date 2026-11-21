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
import wrthub.app.ui.home.HomeScreen
import wrthub.app.ui.home.HomeUiState
import wrthub.app.ui.sites.AddNodeActions
import wrthub.app.ui.sites.AddNodeError
import wrthub.app.ui.sites.AddNodeScreen
import wrthub.app.ui.sites.AddNodeStep
import wrthub.app.ui.sites.AddNodeUiState
import wrthub.app.ui.sites.SitesScreen
import wrthub.app.ui.theme.WrtHubTheme
import wrthub.core.Fixtures

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
}
