package wrthub.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import wrthub.app.R
import wrthub.app.domain.StripLink
import wrthub.app.domain.StripModel
import wrthub.app.domain.StripNode
import wrthub.app.ui.formatBps
import wrthub.app.ui.theme.LocalSemanticColors
import wrthub.app.ui.theme.forSignal

/** The site at a glance; tapping it opens the full network map. */
@Composable
fun TopologyCard(model: StripModel, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val semantic = LocalSemanticColors.current
    Card(onClick = onClick, modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.network_map), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeviceDot(stringResource(R.string.internet), R.drawable.ic_public, online = model.wanUp)
            Connector(
                top = model.wanAddress?.let { buildAnnotatedString { append(it) } },
                bottom = null,
                color = if (model.wanUp) semantic.signalGood else semantic.inactive,
            )
            val router = model.router
            if (router != null) RouterDot(router) else DeviceDot(stringResource(R.string.no_router), R.drawable.ic_router, online = false)
            model.segments.forEach { segment ->
                val link = segment.link
                Connector(
                    top = link?.let { linkRates(it) },
                    bottom = link?.signal?.let { buildAnnotatedString { withStyle(SpanStyle(color = semantic.forSignal(it))) { append("$it dBm") } } },
                    color = if (link == null) semantic.inactive else semantic.forSignal(link.signal),
                )
                DeviceDot(segment.node.label, R.drawable.ic_wifi, online = segment.node.online)
            }
        }
    }
}

@Composable
private fun RouterDot(node: StripNode) = DeviceDot(node.label, R.drawable.ic_router, online = node.online)

@Composable
private fun linkRates(link: StripLink) = buildAnnotatedString {
    val semantic = LocalSemanticColors.current
    link.downKbps?.let { withStyle(SpanStyle(color = semantic.download)) { append("↓" + formatBps(it * 1000.0)) } }
    if (link.downKbps != null && link.upKbps != null) append("\n")
    link.upKbps?.let { withStyle(SpanStyle(color = semantic.upload)) { append("↑" + formatBps(it * 1000.0)) } }
}

@Composable
private fun DeviceDot(label: String, icon: Int, online: Boolean) {
    Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(48.dp)
                .background(
                    if (online) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (online) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun RowScope.Connector(
    top: AnnotatedString?,
    bottom: AnnotatedString?,
    color: Color,
) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            top ?: buildAnnotatedString {},
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
            )
        }
        Text(
            bottom ?: buildAnnotatedString {},
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
