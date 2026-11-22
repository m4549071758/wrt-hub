package wrthub.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import wrthub.app.R
import wrthub.app.domain.StripModel
import wrthub.app.domain.StripSegment
import wrthub.app.ui.formatBps
import wrthub.app.ui.theme.LocalSemanticColors

private val ROW_HEIGHT = 84.dp
private val DEVICE_CENTER = 52.dp
private val LABEL_WIDTH = 104.dp

/**
 * Internet → router → the other nodes, drawn as devices joined by lit lines; a node that does not answer is greyed
 * with its line broken. Tapping it opens the full network map.
 */
@Composable
fun TopologyHero(model: StripModel, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val rows = maxOf(1, model.segments.size)
    val routerY = DEVICE_CENTER + ROW_HEIGHT * (rows - 1) / 2
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT * rows + 48.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.network_map), onClick = onClick),
    ) {
        val internetX = maxWidth * 0.15f
        val routerX = maxWidth * 0.5f
        val apX = maxWidth * 0.85f
        val beam = colors.onSurface
        Canvas(Modifier.matchParentSize()) {
            drawCircle(
                Brush.radialGradient(listOf(beam.copy(alpha = 0.10f), Color.Transparent), center = Offset(routerX.toPx(), routerY.toPx()), radius = size.width * 0.5f),
                radius = size.width * 0.5f,
                center = Offset(routerX.toPx(), routerY.toPx()),
            )
            val wanStart = Offset((internetX + 24.dp).toPx(), routerY.toPx())
            val wanEnd = Offset((routerX - 34.dp).toPx(), routerY.toPx())
            if (model.wanUp) litLine(wanStart, wanEnd, beam, dashed = true) else brokenLine(wanStart, wanEnd, colors.outline)
            model.segments.forEachIndexed { i, segment ->
                val y = (DEVICE_CENTER + ROW_HEIGHT * i).toPx()
                val start = Offset((routerX + 34.dp).toPx(), routerY.toPx())
                val elbow = Offset((routerX + 50.dp).toPx(), y)
                val end = Offset((apX - 20.dp).toPx(), y)
                val online = segment.node.online
                if (rows > 1) {
                    val bend = Offset((routerX + 50.dp).toPx(), routerY.toPx())
                    if (online) litLine(start, bend, beam) else brokenLine(start, bend, colors.outline)
                    if (online) litLine(bend, elbow, beam) else brokenLine(bend, elbow, colors.outline)
                    if (online) litLine(elbow, end, beam) else brokenLine(elbow, end, colors.outline)
                } else if (online) {
                    litLine(start, end, beam)
                } else {
                    brokenLine(start, end, colors.outline)
                }
                if (!online) {
                    val mid = Offset((start.x + end.x) / 2, y)
                    drawCircle(colors.background, 9.dp.toPx(), mid)
                    drawCircle(semantic.signalPoor, 9.dp.toPx(), mid, style = Stroke(1.4.dp.toPx()))
                    val d = 3.5.dp.toPx()
                    drawLine(semantic.signalPoor, mid + Offset(-d, -d), mid + Offset(d, d), 1.6.dp.toPx())
                    drawLine(semantic.signalPoor, mid + Offset(d, -d), mid + Offset(-d, d), 1.6.dp.toPx())
                }
            }
        }
        Globe(Modifier.offset(internetX - 22.dp, routerY - 22.dp))
        RouterArt(online = model.router != null, Modifier.offset(routerX - 32.dp, routerY - 38.dp))
        model.segments.forEachIndexed { i, segment ->
            ApArt(online = segment.node.online, Modifier.offset(apX - 17.dp, DEVICE_CENTER + ROW_HEIGHT * i - 30.dp))
            Label(
                segment.node.label,
                if (segment.node.online) stringResource(if (segment.link != null) R.string.role_mesh else R.string.role_ap) else null,
                if (segment.node.online) null else stringResource(R.string.status_no_response),
                Modifier.offset(apX - LABEL_WIDTH / 2, DEVICE_CENTER + ROW_HEIGHT * i + 36.dp),
            )
            if (segment.link != null && segment.node.online) {
                LinkText(linkText(segment), Modifier.offset((routerX + apX) / 2 + 6.dp - 40.dp, DEVICE_CENTER + ROW_HEIGHT * i - 34.dp))
            }
        }
        Label(stringResource(R.string.internet), "WAN", null, Modifier.offset(internetX - LABEL_WIDTH / 2, routerY + 30.dp))
        Label(model.router?.label ?: stringResource(R.string.no_router), stringResource(R.string.role_gateway), null, Modifier.offset(routerX - LABEL_WIDTH / 2, routerY + 42.dp))
        model.wanAddress?.let { LinkText(it, Modifier.offset((internetX + routerX) / 2 - 40.dp, routerY - 22.dp)) }
    }
}

private fun linkText(segment: StripSegment): String {
    val link = segment.link ?: return ""
    val rate = listOfNotNull(link.downKbps, link.upKbps).maxOrNull()?.let { formatBps(it * 1000.0) }
    val signal = link.signal?.let { "−${-it} dBm" }
    return listOfNotNull(rate, signal).joinToString("\n")
}

private fun DrawScope.litLine(start: Offset, end: Offset, color: Color, dashed: Boolean = false) {
    drawLine(color.copy(alpha = 0.06f), start, end, 10.dp.toPx(), StrokeCap.Round)
    drawLine(color.copy(alpha = 0.12f), start, end, 5.dp.toPx(), StrokeCap.Round)
    drawLine(
        color,
        start,
        end,
        1.6.dp.toPx(),
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 5.dp.toPx())) else null,
    )
}

private fun DrawScope.brokenLine(start: Offset, end: Offset, color: Color) {
    drawLine(color, start, end, 1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 6.dp.toPx())))
}

@Composable
private fun Globe(modifier: Modifier) {
    Box(modifier.size(44.dp).border(1.2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_public), contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

private val DeviceTop = Color(0xFFFFFFFF)
private val DeviceBottom = Color(0xFFC4C7CC)
private val DeviceEdge = Color(0x22000000)
private val DeviceLine = Color(0xFF7A7A7A)

/** A white router with two antennas, 64×76dp. */
@Composable
private fun RouterArt(online: Boolean, modifier: Modifier) {
    val led = if (online) LocalSemanticColors.current.signalGood else DeviceLine
    Canvas(modifier.size(64.dp, 76.dp)) {
        val alpha = if (online) 1f else 0.35f
        drawLine(Color(0xFFD0D3D7).copy(alpha = alpha), Offset(12.dp.toPx(), 28.dp.toPx()), Offset(7.dp.toPx(), 4.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
        drawLine(Color(0xFFD0D3D7).copy(alpha = alpha), Offset(52.dp.toPx(), 28.dp.toPx()), Offset(57.dp.toPx(), 4.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
        body(Offset(0f, 28.dp.toPx()), Size(64.dp.toPx(), 46.dp.toPx()), 9.dp, alpha)
        drawRoundRect(DeviceLine.copy(alpha = alpha), Offset(10.dp.toPx(), 62.dp.toPx()), Size(44.dp.toPx(), 2.5.dp.toPx()), CornerRadius(1.25.dp.toPx()))
        drawCircle(led, 2.dp.toPx(), Offset(16.dp.toPx(), 44.dp.toPx()))
    }
}

/** A white tower access point, 34×60dp. */
@Composable
private fun ApArt(online: Boolean, modifier: Modifier) {
    val led = LocalSemanticColors.current.signalGood
    Canvas(modifier.size(34.dp, 60.dp)) {
        val alpha = if (online) 1f else 0.35f
        body(Offset.Zero, size, 9.dp, alpha)
        drawCircle(Color(0xFF9A9A9A).copy(alpha = alpha), 6.5.dp.toPx(), Offset(17.dp.toPx(), 18.dp.toPx()), style = Stroke(1.2.dp.toPx()))
        if (online) drawCircle(led, 2.dp.toPx(), Offset(17.dp.toPx(), 48.dp.toPx()))
    }
}

private fun DrawScope.body(topLeft: Offset, size: Size, radius: Dp, alpha: Float) {
    val corner = CornerRadius(radius.toPx())
    drawRoundRect(
        Brush.verticalGradient(listOf(DeviceTop.copy(alpha = alpha), DeviceBottom.copy(alpha = alpha)), startY = topLeft.y, endY = topLeft.y + size.height),
        topLeft,
        size,
        corner,
    )
    drawRoundRect(DeviceEdge, topLeft, size, corner, style = Stroke(1.dp.toPx()))
}

@Composable
private fun Label(name: String, role: String?, problem: String?, modifier: Modifier) {
    Column(modifier.width(LABEL_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(
            problem ?: role.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = if (problem != null) LocalSemanticColors.current.signalPoor else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun LinkText(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.width(80.dp),
    )
}
