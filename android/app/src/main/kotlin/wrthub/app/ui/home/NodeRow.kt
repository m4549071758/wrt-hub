package wrthub.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import wrthub.app.R
import wrthub.app.data.NodeConfig
import wrthub.app.domain.NodeState
import wrthub.app.ui.design.AppRow
import wrthub.app.ui.design.StatusText
import wrthub.app.ui.formatUptime
import wrthub.app.ui.theme.LocalSemanticColors
import wrthub.core.model.summary
import wrthub.core.model.wanDevice

/**
 * One node as a tappable row that opens its detail. [state] is null when the node's site is not the loaded one,
 * so only its address is known.
 */
@Composable
fun NodeRow(
    config: NodeConfig,
    state: NodeState?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wifiClients: Int? = null,
    standalone: Boolean = true,
) {
    val semantic = LocalSemanticColors.current
    val online = state as? NodeState.Online
    val icon = when {
        online == null -> R.drawable.ic_router
        online.snapshot.wanDevice() != null -> R.drawable.ic_router
        online.snapshot.wireless.isNotEmpty() -> R.drawable.ic_wifi
        else -> R.drawable.ic_router
    }
    AppRow(
        config.label,
        modifier,
        icon = icon,
        dim = state is NodeState.Unreachable,
        standalone = standalone,
        onClick = onClick,
        subtitle = if (state == null) config.url else null,
        subtitleContent = when (state) {
            is NodeState.Online -> {
                {
                    val uptime = stringResource(R.string.uptime_short, formatUptime(state.snapshot.summary().uptimeSeconds))
                    val clients = wifiClients?.let { stringResource(R.string.wifi_clients_short, it) }
                    StatusText(listOfNotNull(uptime, clients).joinToString(" · "), semantic.signalGood)
                }
            }
            is NodeState.Unreachable -> {
                { StatusText(stringResource(R.string.status_unreachable), semantic.signalPoor) }
            }
            null -> null
        },
    )
}
