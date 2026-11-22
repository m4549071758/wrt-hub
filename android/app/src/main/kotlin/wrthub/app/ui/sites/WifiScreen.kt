package wrthub.app.ui.sites

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.ui.design.AppCheck
import wrthub.app.ui.design.AppRow
import wrthub.app.ui.design.AppTopBar
import wrthub.app.ui.design.GroupDivider
import wrthub.app.ui.design.PrimaryButton
import wrthub.app.ui.design.RowGroup
import wrthub.app.ui.design.ScreenPadding
import wrthub.app.ui.design.SectionHeader
import wrthub.app.ui.design.StatusText
import wrthub.app.ui.design.SubtitleText
import wrthub.app.ui.design.UnderlineField
import wrthub.app.ui.theme.LocalSemanticColors

data class WifiActions(
    val onSsid: (String) -> Unit,
    val onKey: (String) -> Unit,
    val validate: () -> Boolean,
    val onApply: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun WifiRoute(viewModel: WifiViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WifiScreen(state, WifiActions(viewModel::onSsid, viewModel::onKey, viewModel::validate, viewModel::apply, onBack))
}

@Composable
fun WifiScreen(state: WifiUiState, actions: WifiActions) {
    var confirming by remember { mutableStateOf(false) }
    val nodes = state.site?.nodes.orEmpty()
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        AppTopBar(
            title = stringResource(R.string.site_wifi),
            subtitle = state.site?.let { site -> { SubtitleText(stringResource(R.string.site_wifi_summary, site.name, nodes.size)) } },
            onBack = actions.onBack,
            actions = {
                if (state.applying) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
            },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
        ) {
            WifiGlyph(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp).size(120.dp, 100.dp))
            UnderlineField(
                stringResource(R.string.current_ssid),
                state.currentSsids.joinToString(", ").ifEmpty { "—" },
                onValueChange = {},
                enabled = false,
            )
            UnderlineField(
                stringResource(R.string.new_ssid),
                state.ssid,
                actions.onSsid,
                enabled = !state.applying,
                error = if (state.inputError == WifiInputError.SSID) stringResource(R.string.error_ssid) else null,
            )
            UnderlineField(
                stringResource(R.string.new_password),
                state.key,
                actions.onKey,
                enabled = !state.applying,
                error = if (state.inputError == WifiInputError.KEY) stringResource(R.string.error_psk) else null,
                password = true,
            )
            if (nodes.isNotEmpty()) {
                SectionHeader(stringResource(R.string.wifi_target_aps), Modifier.padding(top = 8.dp))
                RowGroup {
                    nodes.forEachIndexed { index, node ->
                        if (index > 0) GroupDivider()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppCheck(checked = true)
                            Spacer(Modifier.width(14.dp))
                            Text(node.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            state.results?.let { results ->
                SectionHeader(stringResource(R.string.wifi_results), Modifier.padding(top = 8.dp))
                RowGroup {
                    results.forEachIndexed { index, result ->
                        if (index > 0) GroupDivider()
                        AppRow(result.label, subtitleContent = { OutcomeText(result.outcome) })
                    }
                }
            }
            Spacer(Modifier.heightIn(min = 16.dp))
        }
        PrimaryButton(
            pluralStringResource(R.plurals.wifi_apply_to, nodes.size, nodes.size),
            onClick = { if (actions.validate()) confirming = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 16.dp),
            enabled = !state.applying && state.site != null,
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.wifi_confirm_title)) },
            text = { Text(stringResource(R.string.wifi_confirm_text, state.ssid)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    actions.onApply()
                }) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Three arcs and a dot in the text color over a faint wider stroke, so the glyph seems to glow. */
@Composable
private fun WifiGlyph(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val dot = Offset(size.width / 2, size.height * 0.74f)
        val halo = 44.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.10f), color.copy(alpha = 0f)), center, halo), halo, center)
        // Two faint wide strokes under the line make the glow.
        for ((width, alpha) in listOf(10.dp.toPx() to 0.07f, 5.dp.toPx() to 0.14f, 2.dp.toPx() to 1f)) {
            for (step in 1..3) {
                val radius = 20.dp.toPx() * step
                drawArc(
                    color.copy(alpha = alpha),
                    startAngle = -135f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(dot.x - radius, dot.y - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = width, cap = StrokeCap.Round),
                )
            }
            drawCircle(color.copy(alpha = alpha), radius = 1.5.dp.toPx() + width / 2, center = dot)
        }
    }
}

@Composable
private fun OutcomeText(outcome: WifiOutcome) {
    val semantic = LocalSemanticColors.current
    when (outcome) {
        is WifiOutcome.Changed -> StatusText(stringResource(R.string.wifi_changed, outcome.interfaces), semantic.signalGood)
        WifiOutcome.NoAccessPoint -> StatusText(stringResource(R.string.wifi_no_ap), semantic.inactive)
        WifiOutcome.Unchanged -> StatusText(stringResource(R.string.wifi_unchanged), semantic.inactive)
        WifiOutcome.NotApplied -> StatusText(stringResource(R.string.wifi_not_applied), semantic.signalFair)
        is WifiOutcome.Failed -> StatusText(stringResource(R.string.operation_failed, outcome.reason), MaterialTheme.colorScheme.error)
    }
}
