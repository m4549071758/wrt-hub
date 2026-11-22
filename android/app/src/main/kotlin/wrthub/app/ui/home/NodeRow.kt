package wrthub.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import wrthub.app.R
import wrthub.app.data.NodeConfig
import wrthub.app.domain.NodeState
import wrthub.app.ui.formatUptime
import wrthub.app.ui.theme.LocalSemanticColors
import wrthub.core.model.summary
import wrthub.core.model.wanDevice

/**
 * One node as a tappable row that opens its detail. [state] is null when the node's site is not the loaded one,
 * so only its address is known.
 */
@Composable
fun NodeRow(config: NodeConfig, state: NodeState?, onClick: () -> Unit, modifier: Modifier = Modifier, wifiClients: Int? = null) {
    val semantic = LocalSemanticColors.current
    val online = state as? NodeState.Online
    val icon = when {
        online == null -> R.drawable.ic_router
        online.snapshot.wanDevice() != null -> R.drawable.ic_router
        online.snapshot.wireless.isNotEmpty() -> R.drawable.ic_wifi
        else -> R.drawable.ic_router
    }
    val status: String
    val statusColor: Color
    when (state) {
        is NodeState.Online -> {
            val uptime = stringResource(R.string.uptime_short, formatUptime(state.snapshot.summary().uptimeSeconds))
            val clients = wifiClients?.let { stringResource(R.string.wifi_clients_short, it) }
            status = listOfNotNull(stringResource(R.string.status_online), uptime, clients).joinToString(" · ")
            statusColor = semantic.signalGood
        }
        is NodeState.Unreachable -> {
            status = stringResource(R.string.status_unreachable)
            statusColor = semantic.signalPoor
        }
        null -> {
            status = config.url
            statusColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = {
            Icon(painterResource(icon), contentDescription = null, tint = if (state is NodeState.Unreachable) semantic.inactive else MaterialTheme.colorScheme.primary)
        },
        supportingContent = { Text(status, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
    ) {
        Text(config.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
