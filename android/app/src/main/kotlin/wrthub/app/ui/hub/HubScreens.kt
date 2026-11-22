package wrthub.app.ui.hub

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import wrthub.app.ui.theme.LocalSemanticColors
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import wrthub.app.R
import wrthub.app.ui.design.AlertCard
import wrthub.app.ui.design.AppRow
import wrthub.app.ui.design.AppTopBar
import wrthub.app.ui.design.GroupDivider
import wrthub.app.ui.design.KeyValueCard
import wrthub.app.ui.design.ListPadding
import wrthub.app.ui.design.PlainIconButton
import wrthub.app.ui.design.RowGroup
import wrthub.app.ui.design.ScreenPadding
import wrthub.app.ui.design.SegmentedToggle
import wrthub.app.ui.design.StatCard
import wrthub.app.ui.design.StatusText
import wrthub.app.ui.design.SubtitleText
import wrthub.app.ui.design.ValueText
import wrthub.app.ui.formatBps
import wrthub.app.ui.formatUptime
import wrthub.core.hub.StarlinkData

/** What happened, in a sentence; shared by the events screen and the notifications. */
fun eventText(res: Resources, kind: String, nodeLabel: String?, newDevices: Int): String {
    val node = nodeLabel ?: res.getString(R.string.event_some_node)
    return when (kind) {
        "node_offline" -> res.getString(R.string.event_node_offline, node)
        "node_online" -> res.getString(R.string.event_node_online, node)
        "wan_down" -> res.getString(R.string.event_wan_down, node)
        "wan_up" -> res.getString(R.string.event_wan_up, node)
        "new_client" -> res.getQuantityString(R.plurals.event_new_client, newDevices, newDevices)
        else -> kind
    }
}

private val warnings = setOf("node_offline", "wan_down")

private fun eventIcon(kind: String) = when (kind) {
    "node_offline", "node_online" -> R.drawable.ic_router
    "wan_down", "wan_up" -> R.drawable.ic_public
    "new_client" -> R.drawable.ic_devices
    else -> R.drawable.ic_hub
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

@Composable
fun EventsRoute(viewModel: EventsViewModel, pushAvailable: Boolean, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EventsScreen(state, pushAvailable, onRefresh = viewModel::refresh, onBack = onBack)
}

/** [zone] is the phone's own unless a test pins it. */
@Composable
fun EventsScreen(state: EventsUiState, pushAvailable: Boolean, onRefresh: () -> Unit, onBack: () -> Unit, zone: ZoneId = ZoneId.systemDefault()) {
    val res = LocalContext.current.resources
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(
            stringResource(R.string.hub_events),
            subtitle = { SubtitleText(state.siteName) },
            onBack = onBack,
            actions = {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).padding(4.dp), strokeWidth = 2.dp)
                } else {
                    PlainIconButton(R.drawable.ic_refresh, stringResource(R.string.refresh), onRefresh)
                }
            },
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
            if (!pushAvailable) {
                item { AlertCard(stringResource(R.string.push_unavailable_title), stringResource(R.string.push_unavailable_body), Modifier.padding(top = 8.dp)) }
            }
            state.error?.let { error ->
                item { AlertCard(stringResource(R.string.hub_error_unreachable), error, Modifier.padding(top = 8.dp)) }
            }
            if (!state.loading && state.error == null && state.events.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.hub_no_events),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (state.events.isNotEmpty()) {
                item {
                    RowGroup(Modifier.padding(top = 8.dp)) {
                        state.events.forEachIndexed { index, event ->
                            if (index > 0) GroupDivider()
                            AppRow(
                                eventText(res, event.kind, event.nodeLabel, event.newDevices),
                                icon = eventIcon(event.kind),
                                iconTint = if (event.kind in warnings) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                subtitle = timeFormat.withLocale(Locale.getDefault()).format(Instant.ofEpochMilli(event.at).atZone(zone)),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun StarlinkData.values(name: String): List<Double> =
    series.firstOrNull { it.field == "starlink_dish_$name" }?.points?.mapNotNull { it.getOrNull(1) } ?: emptyList()

private fun fixed(value: Double?, digits: Int) = value?.let { String.format(Locale.ROOT, "%.${digits}f", it) } ?: "-"

/** formatBps's "12.3 Mbps" as the number and its unit. */
private fun rate(bps: Double?): Pair<String, String?> =
    bps?.let { formatBps(it).split(" ", limit = 2).let { (n, u) -> n to u } } ?: ("-" to null)

@Composable
fun StarlinkRoute(viewModel: StarlinkViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StarlinkScreen(state, onRange = viewModel::select, onRefresh = viewModel::load, onBack = onBack)
}

@Composable
fun StarlinkScreen(state: StarlinkUiState, onRange: (StarlinkRange) -> Unit, onRefresh: () -> Unit, onBack: () -> Unit) {
    val data = state.data
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(
            stringResource(R.string.starlink),
            subtitle = { SubtitleText(state.siteName) },
            onBack = onBack,
            actions = {
                if (state.loading) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).padding(4.dp), strokeWidth = 2.dp)
                } else {
                    PlainIconButton(R.drawable.ic_refresh, stringResource(R.string.refresh), onRefresh)
                }
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SegmentedToggle(
                listOf(StarlinkRange.M15 to "15m", StarlinkRange.H1 to "1h", StarlinkRange.H6 to "6h", StarlinkRange.H24 to "24h"),
                state.range,
                onRange,
                Modifier.fillMaxWidth(),
            )
            state.error?.let { AlertCard(stringResource(R.string.starlink_unavailable), it) }
            if (data != null) StarlinkCards(data)
        }
    }
}

@Composable
private fun StarlinkCards(data: StarlinkData) {
    val up = data.values("up").lastOrNull()
    val uptime = data.values("uptime_seconds").lastOrNull()
    KeyValueCard(
        listOf(
            stringResource(R.string.starlink_status) to {
                when (up) {
                    null -> ValueText("-")
                    1.0 -> StatusText(stringResource(R.string.status_online), LocalSemanticColors.current.signalGood)
                    else -> StatusText(stringResource(R.string.state_offline), LocalSemanticColors.current.signalPoor)
                }
            },
            stringResource(R.string.uptime) to { ValueText(uptime?.let { formatUptime(it.toLong()) } ?: "-") },
        ),
    )
    val latency = data.values("pop_ping_latency_seconds").map { it * 1000 }
    val loss = data.values("pop_ping_drop_ratio").map { it * 100 }
    val down = data.values("downlink_throughput_bps")
    val upload = data.values("uplink_throughput_bps")
    val obstruction = data.values("fraction_obstruction_ratio").map { it * 100 }
    val obstructed24h = data.values("last_24h_obstructed_seconds").lastOrNull()
    val power = data.values("power_input_watts")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(stringResource(R.string.starlink_latency), fixed(latency.lastOrNull(), 0), Modifier.weight(1f), unit = "ms", history = latency)
        StatCard(stringResource(R.string.starlink_loss), fixed(loss.lastOrNull(), 1), Modifier.weight(1f), unit = "%", history = loss)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val (downValue, downUnit) = rate(down.lastOrNull())
        val (upValue, upUnit) = rate(upload.lastOrNull())
        StatCard(stringResource(R.string.starlink_download), downValue, Modifier.weight(1f), unit = downUnit, history = down)
        StatCard(stringResource(R.string.starlink_upload), upValue, Modifier.weight(1f), unit = upUnit, history = upload)
    }
    // Only the obstruction card has a caption; the row takes its height so the pair lines up.
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(
            stringResource(R.string.starlink_obstruction),
            fixed(obstruction.lastOrNull(), 1),
            Modifier.weight(1f).fillMaxHeight(),
            unit = "%",
            caption = obstructed24h?.let { stringResource(R.string.starlink_obstructed_24h, formatUptime(it.toLong())) },
            history = obstruction,
        )
        StatCard(stringResource(R.string.starlink_power), fixed(power.lastOrNull(), 0), Modifier.weight(1f).fillMaxHeight(), unit = "W", history = power)
    }
}
