package wrthub.app.ui.admin

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import wrthub.app.R
import wrthub.app.domain.AdminFailure
import wrthub.core.direct.WriteOutcome

/** What an admin screen reports once in a snackbar. */
sealed interface AdminNotice {
    data class Written(val outcome: WriteOutcome) : AdminNotice

    data class Failed(val failure: AdminFailure) : AdminNotice

    data class ServiceDone(val service: String, val action: Int) : AdminNotice
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScaffold(
    title: String,
    notice: AdminNotice?,
    onDismissNotice: () -> Unit,
    onBack: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    floatingActionButton: @Composable () -> Unit = {},
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
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
                actions = {
                    if (onRefresh != null) {
                        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh)) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = floatingActionButton,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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
