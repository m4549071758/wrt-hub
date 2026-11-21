package wrthub.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import wrthub.app.R
import wrthub.app.domain.Rate
import wrthub.app.ui.formatBps
import wrthub.app.ui.theme.LocalSemanticColors

@Composable
fun BandwidthRow(rates: List<Rate>, error: String?, modifier: Modifier = Modifier) {
    val semantic = LocalSemanticColors.current
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RateCard(stringResource(R.string.download), "↓", rates.map { it.rxBps }, semantic.download, Modifier.weight(1f))
            RateCard(stringResource(R.string.upload), "↑", rates.map { it.txBps }, semantic.upload, Modifier.weight(1f))
        }
        if (error != null) {
            Text(
                stringResource(R.string.realtime_unavailable, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun RateCard(title: String, arrow: String, values: List<Double>, color: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text("$arrow $title", style = MaterialTheme.typography.labelLarge, color = color)
            Text(values.lastOrNull()?.let(::formatBps) ?: "—", style = MaterialTheme.typography.titleLarge)
            Sparkline(values, color, Modifier.fillMaxWidth().height(48.dp).padding(top = 8.dp))
        }
    }
}

@Composable
fun Sparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = values.max().takeIf { it > 0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val line = Path()
        values.forEachIndexed { i, value ->
            val x = i * stepX
            val y = size.height - (value / max * size.height).toFloat()
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
    }
}
