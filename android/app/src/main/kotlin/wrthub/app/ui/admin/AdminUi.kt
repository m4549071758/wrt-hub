package wrthub.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import wrthub.app.R
import wrthub.app.domain.AdminFailure
import wrthub.app.ui.design.AppTopBar
import wrthub.app.ui.design.RingButton
import wrthub.app.ui.design.SubtitleText
import wrthub.core.direct.WriteOutcome

/** What an admin screen reports once in a snackbar. */
sealed interface AdminNotice {
    data class Written(val outcome: WriteOutcome) : AdminNotice

    data class Failed(val failure: AdminFailure) : AdminNotice

    data class ServiceDone(val service: String, val action: Int) : AdminNotice
}

/** An admin page: the top bar with back and refresh, a snackbar for [notice], and the page content. */
@Composable
fun AdminScaffold(
    title: String,
    notice: AdminNotice?,
    onDismissNotice: () -> Unit,
    onBack: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
    subtitle: String? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val text = notice?.let { noticeText(it) }
    LaunchedEffect(notice) {
        if (text != null) {
            snackbar.showSnackbar(text)
            onDismissNotice()
        }
    }
    Scaffold(
        topBar = {
            AppTopBar(
                title = title,
                subtitle = subtitle?.let { { SubtitleText(it) } },
                onBack = onBack,
                actions = {
                    if (onRefresh != null) RingButton(R.drawable.ic_refresh, stringResource(R.string.refresh), onRefresh)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = floatingActionButton,
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@Composable
private fun noticeText(notice: AdminNotice): String = when (notice) {
    is AdminNotice.Written -> when (notice.outcome) {
        WriteOutcome.APPLIED -> stringResource(R.string.notice_write_applied)
        WriteOutcome.ROLLED_BACK -> stringResource(R.string.notice_write_rolled_back)
        WriteOutcome.UNCHANGED -> stringResource(R.string.notice_write_unchanged)
    }
    is AdminNotice.Failed -> failureText(notice.failure)
    is AdminNotice.ServiceDone -> stringResource(R.string.notice_service_done, notice.service, stringResource(notice.action))
}

@Composable
fun failureText(failure: AdminFailure): String = when (failure) {
    is AdminFailure.Invalid -> stringResource(R.string.failure_invalid, failure.reason)
    is AdminFailure.Pending -> stringResource(R.string.failure_pending, failure.configs.sorted().joinToString())
    is AdminFailure.Unreachable -> stringResource(R.string.operation_failed, failure.reason)
    is AdminFailure.Denied -> stringResource(R.string.denied_calls, failure.call)
}

/** The M3 switch in the app's monochrome colors. */
@Composable
fun AdminSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = colors.onSurface,
            checkedThumbColor = colors.background,
            uncheckedTrackColor = colors.surfaceVariant,
            uncheckedBorderColor = colors.outline,
        ),
    )
}

/**
 * A labelled value over an underline, like an UnderlineField, that opens a menu of [options]; [labelOf] renders
 * both the value and the options.
 */
@Composable
fun <T> Selector(label: String, value: T, options: List<T>, labelOf: @Composable (T) -> String, onSelect: (T) -> Unit, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    SelectorField(label, labelOf(value), enabled && options.isNotEmpty(), onClick = { open = true }) {
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(labelOf(option)) }, onClick = {
                    open = false
                    onSelect(option)
                })
            }
        }
    }
}

/** The look of [Selector] for a value picked elsewhere, such as in a dialog; [menu] is anchored to the value. */
@Composable
fun SelectorField(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    error: String? = null,
    menu: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (error != null) colors.error else colors.onSurfaceVariant)
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, role = Role.DropdownList, onClick = onClick)
                    .heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            menu()
        }
        HorizontalDivider(color = if (error != null) colors.error else colors.outline)
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
