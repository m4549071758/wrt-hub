package wrthub.app.ui.node

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import wrthub.app.R
import wrthub.app.domain.NodeState
import wrthub.app.ui.design.AlertCard
import wrthub.app.ui.design.AppCard
import wrthub.app.ui.design.AppRow
import wrthub.app.ui.design.AppTopBar
import wrthub.app.ui.design.GroupDivider
import wrthub.app.ui.design.KeyValueCard
import wrthub.app.ui.design.PrimaryButton
import wrthub.app.ui.design.RowGroup
import wrthub.app.ui.design.ScreenPadding
import wrthub.app.ui.design.SecondaryButton
import wrthub.app.ui.design.SectionHeader
import wrthub.app.ui.design.SlideToConfirm
import wrthub.app.ui.design.StatCard
import wrthub.app.ui.design.StatusText
import wrthub.app.ui.design.SubtitleText
import wrthub.app.ui.design.UnderlineField
import wrthub.app.ui.design.ValueText
import wrthub.app.ui.formatBytes
import wrthub.app.ui.formatUptime
import wrthub.app.ui.theme.LocalSemanticColors
import wrthub.core.admin.parseDhcpConfig
import wrthub.core.model.NodeSummary
import wrthub.core.model.WirelessMode
import wrthub.core.model.netInterfaces
import wrthub.core.model.summary
import wrthub.core.model.wirelessInterfaces
import wrthub.core.snapshot.Snapshot

data class NodeDetailActions(
    val onHost: (String) -> Unit,
    val onPing: () -> Unit,
    val onTraceroute: () -> Unit,
    val onReboot: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onBack: () -> Unit,
    val onOpen: (AdminPage) -> Unit = {},
)

/** The LuCI-replacement pages reachable from a node. */
enum class AdminPage { STATUS, LEASES, FORWARDS, WIRELESS, WAN, SYSTEM, PACKAGES }

@Composable
fun NodeDetailRoute(viewModel: NodeDetailViewModel, onBack: () -> Unit, onOpen: (AdminPage) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.setLive(true)
        onPauseOrDispose { viewModel.setLive(false) }
    }
    NodeDetailScreen(
        state,
        NodeDetailActions(viewModel::onHost, viewModel::ping, viewModel::traceroute, viewModel::reboot, viewModel::dismissNotice, onBack, onOpen),
    )
}

@Composable
fun NodeDetailScreen(state: NodeDetailUiState, actions: NodeDetailActions) {
    val snackbar = remember { SnackbarHostState() }
    val notice = state.notice?.let { noticeText(it) }
    LaunchedEffect(state.notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            actions.onDismissNotice()
        }
    }
    val node = state.node
    val snapshot = (node as? NodeState.Online)?.snapshot
    val summary = remember(snapshot) { snapshot?.summary() }
    val facts = remember(snapshot) { snapshot?.let(::settingFacts) }
    Scaffold(
        topBar = {
            AppTopBar(
                title = node?.config?.label.orEmpty(),
                subtitle = (snapshot?.let(::lanMac) ?: summary?.model)?.takeIf { it.isNotEmpty() }?.let { { SubtitleText(it) } },
                onBack = actions.onBack,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp),
        ) {
            if (node is NodeState.Unreachable) {
                AlertCard(stringResource(R.string.node_unreachable_title, node.config.label), node.reason, Modifier.padding(top = 4.dp))
            }
            if (summary != null) Stats(summary, state, Modifier.padding(top = 4.dp))
            if (node != null) {
                SectionHeader(stringResource(R.string.overview))
                Overview(node, summary)
            }
            if (state.admin.status) {
                SettingsSection(state.admin, facts, actions)
            }
            SectionHeader(stringResource(R.string.section_status_diagnostics))
            Diagnostics(state, actions)
            SectionHeader(stringResource(R.string.reboot))
            SlideToConfirm(
                stringResource(R.string.slide_to_reboot),
                onConfirm = actions.onReboot,
                enabled = !state.running && node != null,
            )
        }
    }
}

@Composable
private fun Stats(summary: NodeSummary, state: NodeDetailUiState, modifier: Modifier) {
    val total = summary.memoryTotalBytes
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard(
            stringResource(R.string.load),
            String.format(Locale.ROOT, "%.2f", summary.load1),
            Modifier.weight(1f),
            caption = stringResource(R.string.load_1min),
            history = state.loadHistory,
        )
        StatCard(
            stringResource(R.string.memory),
            if (total > 0) "${100 * (total - summary.memoryAvailableBytes) / total}" else "—",
            Modifier.weight(1f),
            unit = if (total > 0) "%" else null,
            caption = if (total > 0) stringResource(R.string.memory_used_of, formatBytes(total)) else null,
            history = state.memoryHistory,
        )
    }
}

@Composable
private fun Overview(node: NodeState, summary: NodeSummary?) {
    val semantic = LocalSemanticColors.current
    val online = node is NodeState.Online
    val status = stringResource(if (online) R.string.status_online else R.string.status_unreachable)
    val rows = buildList<Pair<String, @Composable () -> Unit>> {
        add(stringResource(R.string.status) to { StatusText(status, if (online) semantic.signalGood else semantic.signalPoor) })
        if (summary != null) {
            add(stringResource(R.string.model) to { ValueText(summary.model) })
            add(stringResource(R.string.firmware) to { ValueText(summary.firmware) })
            add(stringResource(R.string.uptime) to { ValueText(formatUptime(summary.uptimeSeconds)) })
        }
        add(stringResource(R.string.address) to { ValueText(node.config.url.substringAfter("://").trimEnd('/')) })
    }
    KeyValueCard(rows)
    val denied = (node as? NodeState.Online)?.snapshot?.deniedCalls.orEmpty()
    if (denied.isNotEmpty()) {
        Text(
            stringResource(R.string.denied_calls, denied.joinToString(", ")),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
    }
}

@Composable
private fun SettingsSection(admin: AdminEntries, facts: SettingFacts?, actions: NodeDetailActions) {
    val leases = facts?.staticLeases?.let { pluralStringResource(R.plurals.entry_count, it, it) }
    val entries = listOfNotNull(
        Entry(R.drawable.ic_wifi, R.string.wireless, facts?.wireless, AdminPage.WIRELESS).takeIf { admin.wireless },
        Entry(R.drawable.ic_dns, R.string.static_leases, leases, AdminPage.LEASES).takeIf { admin.staticLeases },
        Entry(R.drawable.ic_swap_horiz, R.string.port_forwards, null, AdminPage.FORWARDS).takeIf { admin.portForwards },
        Entry(R.drawable.ic_public, R.string.wan, null, AdminPage.WAN).takeIf { admin.wan },
        Entry(R.drawable.ic_settings, R.string.system, null, AdminPage.SYSTEM).takeIf { admin.system },
        Entry(R.drawable.ic_inventory_2, R.string.packages, null, AdminPage.PACKAGES).takeIf { admin.packages },
    )
    if (entries.isEmpty()) return
    SectionHeader(stringResource(R.string.section_settings))
    RowGroup {
        entries.forEachIndexed { index, entry ->
            if (index > 0) GroupDivider()
            AppRow(stringResource(entry.title), icon = entry.icon, subtitle = entry.value, onClick = { actions.onOpen(entry.page) })
        }
    }
}

private class Entry(val icon: Int, val title: Int, val value: String?, val page: AdminPage)

@Composable
private fun Diagnostics(state: NodeDetailUiState, actions: NodeDetailActions) {
    if (state.admin.status) {
        AppRow(
            stringResource(R.string.status),
            icon = R.drawable.ic_monitoring,
            onClick = { actions.onOpen(AdminPage.STATUS) },
            standalone = true,
        )
    }
    UnderlineField(
        stringResource(R.string.target_host),
        state.host,
        actions.onHost,
        Modifier.padding(top = 4.dp),
        enabled = !state.running,
        keyboardType = KeyboardType.Uri,
    )
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton("ping", actions.onPing, Modifier.weight(1f), enabled = !state.running, height = 44)
        SecondaryButton("traceroute", actions.onTraceroute, Modifier.weight(1f), enabled = !state.running, height = 44)
        if (state.running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
    state.result?.let { result ->
        AppCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "$ ${result.title}  (exit ${result.exitCode})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    result.output,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Current values shown next to the settings entries; null where the snapshot does not say. */
private class SettingFacts(val wireless: String?, val staticLeases: Int?)

private fun settingFacts(snapshot: Snapshot): SettingFacts {
    val radios = snapshot.wirelessInterfaces().filter { it.mode != WirelessMode.OTHER }
    val channels = radios.mapNotNull { it.channel }.distinct().sorted().map { "ch$it" }
    val ssids = radios.filter { it.mode == WirelessMode.AP }.mapNotNull { it.ssid }.distinct().size
    val wireless = (channels + listOfNotNull(ssids.takeIf { it > 0 }?.let { "SSID $it" })).joinToString(" · ").ifEmpty { null }
    return SettingFacts(wireless, snapshot.dhcpConfig?.let(::parseDhcpConfig)?.leases?.size)
}

/** The MAC of the LAN device, which identifies the node on its label. */
private fun lanMac(snapshot: Snapshot): String? {
    val device = snapshot.netInterfaces().firstOrNull { it.name == "lan" }?.device ?: return null
    return ((snapshot.devices[device] as? JsonObject)?.get("macaddr") as? JsonPrimitive)?.content?.lowercase()
}

@Composable
private fun noticeText(notice: NodeNotice): String = when (notice) {
    NodeNotice.InvalidHost -> stringResource(R.string.error_invalid_host)
    NodeNotice.RebootRequested -> stringResource(R.string.notice_reboot_requested)
    is NodeNotice.Failed -> stringResource(R.string.operation_failed, notice.reason)
}
