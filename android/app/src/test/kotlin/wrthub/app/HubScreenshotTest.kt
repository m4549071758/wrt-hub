package wrthub.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.ZoneOffset
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import wrthub.app.ui.hub.AddHubActions
import wrthub.app.ui.hub.AddHubError
import wrthub.app.ui.hub.AddHubScreen
import wrthub.app.ui.hub.AddHubStep
import wrthub.app.ui.hub.AddHubUiState
import wrthub.app.ui.hub.AddSiteScreen
import wrthub.app.ui.hub.EventItem
import wrthub.app.ui.hub.EventsScreen
import wrthub.app.ui.hub.EventsUiState
import wrthub.app.ui.hub.StarlinkRange
import wrthub.app.ui.hub.StarlinkScreen
import wrthub.app.ui.hub.StarlinkUiState
import wrthub.app.ui.theme.WrtHubTheme
import wrthub.core.hub.HubNodeInfo
import wrthub.core.hub.HubSite
import wrthub.core.hub.StarlinkData
import wrthub.core.hub.StarlinkSeries

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-xxhdpi")
class HubScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = AddHubActions({}, {}, {}, {}, {}, {}, {}, {})

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { WrtHubTheme(darkTheme = dark, content = content) }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    @Config(qualifiers = "+ja")
    fun addSiteChooser() = capture("hub_add_site_ja") { AddSiteScreen({}, {}, {}) }

    @Test
    @Config(qualifiers = "+ja")
    fun addHubForm() = capture("hub_add_form_ja") {
        AddHubScreen(
            AddHubUiState(server = "hub.example.com", code = "ABCD-EFGH-JKLM", error = AddHubError.PAIRING_REJECTED),
            actions,
        )
    }

    @Test
    fun addHubWithoutScanner() = capture("hub_add_form_no_scanner") {
        AddHubScreen(AddHubUiState(), actions.copy(onScan = null))
    }

    @Test
    fun chooseSites() = capture("hub_choose_sites") {
        val sites = listOf(
            HubSite("cabin", "Cabin", listOf(HubNodeInfo("r", "Router", "fresh"), HubNodeInfo("a", "Hall AP", "stale"))),
            HubSite("office", "Office", listOf(HubNodeInfo("o", "Gateway", "fresh"))),
        )
        AddHubScreen(AddHubUiState(step = AddHubStep.ChooseSites(sites), chosen = setOf("cabin")), actions)
    }

    private val base = 1_795_000_000_000L

    @Test
    @Config(qualifiers = "+ja")
    fun events() = capture("hub_events_ja") {
        EventsScreen(
            EventsUiState(
                siteName = "Cabin",
                events = listOf(
                    EventItem(5, "node_online", "Hall AP", 0, base),
                    EventItem(4, "new_client", null, 2, base - 600_000),
                    EventItem(3, "wan_up", "Router", 0, base - 3_600_000),
                    EventItem(2, "wan_down", "Router", 0, base - 3_900_000),
                    EventItem(1, "node_offline", "Hall AP", 0, base - 7_200_000),
                ),
                loading = false,
            ),
            pushAvailable = false,
            onRefresh = {},
            onBack = {},
            zone = ZoneOffset.UTC,
        )
    }

    private fun series(field: String, value: (Int) -> Double) =
        StarlinkSeries("starlink_dish_$field", List(60) { listOf((base + it * 60_000).toDouble(), value(it)) })

    @Test
    fun starlinkDark() = capture("hub_starlink_dark", dark = true) {
        StarlinkScreen(
            StarlinkUiState(
                siteName = "Cabin",
                range = StarlinkRange.H1,
                loading = false,
                data = StarlinkData(
                    "1h",
                    listOf(
                        series("up") { 1.0 },
                        series("uptime_seconds") { 93_000.0 + it * 60 },
                        series("pop_ping_latency_seconds") { 0.032 + 0.008 * sin(it / 4.0) },
                        series("pop_ping_drop_ratio") { 0.004 + 0.004 * cos(it / 6.0) },
                        series("downlink_throughput_bps") { 6.0e7 + 4.0e7 * sin(it / 5.0) },
                        series("uplink_throughput_bps") { 8.0e6 + 3.0e6 * cos(it / 3.0) },
                        series("fraction_obstruction_ratio") { 0.012 },
                        series("last_24h_obstructed_seconds") { 340.0 },
                        series("power_input_watts") { 48.0 + 6 * sin(it / 7.0) },
                    ),
                ),
            ),
            onRange = {},
            onRefresh = {},
            onBack = {},
        )
    }
}
