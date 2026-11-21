package wrthub.app.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import wrthub.app.R
import wrthub.app.domain.NodeState
import wrthub.app.ui.formatUptime
import wrthub.app.ui.formatUsedPercent
import wrthub.app.ui.theme.LocalSemanticColors
import wrthub.core.model.summary
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow

/** [wifiClients] is null for nodes without an AP interface; [onClick] makes the card open the node. */
@Composable
fun NodeCard(state: NodeState, wifiClients: Int?, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val semantic = LocalSemanticColors.current
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(clickable.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.config.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(10.dp)
                        .background(if (state is NodeState.Online) semantic.signalGood else semantic.signalPoor, CircleShape),
                )
                if (onClick != null) {
                    Icon(
                        painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).size(20.dp),
                    )
                }
            }
            when (state) {
                is NodeState.Online -> {
                    val summary = state.snapshot.summary()
                    InfoRow(R.string.model, summary.model)
                    InfoRow(R.string.firmware, summary.firmware)
                    InfoRow(R.string.uptime, formatUptime(summary.uptimeSeconds))
                    InfoRow(R.string.load, String.format(Locale.ROOT, "%.2f", summary.load1))
                    InfoRow(R.string.memory, formatUsedPercent(summary.memoryAvailableBytes, summary.memoryTotalBytes))
                    if (wifiClients != null) InfoRow(R.string.wifi_clients, wifiClients.toString())
                    if (state.snapshot.deniedCalls.isNotEmpty()) {
                        Text(
                            stringResource(R.string.denied_calls, state.snapshot.deniedCalls.joinToString(", ")),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                is NodeState.Unreachable -> Text(
                    stringResource(R.string.node_unreachable, state.reason),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(@StringRes label: Int, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
