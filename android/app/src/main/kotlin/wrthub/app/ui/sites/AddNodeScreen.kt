package wrthub.app.ui.sites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.ui.design.AlertCard
import wrthub.app.ui.design.AppTopBar
import wrthub.app.ui.design.PrimaryButton
import wrthub.app.ui.design.ScreenPadding
import wrthub.app.ui.design.UnderlineField

data class AddNodeActions(
    val onSiteName: (String) -> Unit,
    val onLabel: (String) -> Unit,
    val onUrl: (String) -> Unit,
    val onUsername: (String) -> Unit,
    val onPassword: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onTrust: () -> Unit,
    val onReject: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun AddNodeRoute(viewModel: AddNodeViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.step) { if (state.step == AddNodeStep.Done) onDone() }
    AddNodeScreen(
        state,
        AddNodeActions(
            viewModel::onSiteName, viewModel::onLabel, viewModel::onUrl, viewModel::onUsername, viewModel::onPassword,
            viewModel::submit, viewModel::trustCertificate, viewModel::rejectCertificate, onBack,
        ),
    )
}

@Composable
fun AddNodeScreen(state: AddNodeUiState, actions: AddNodeActions) {
    val editable = state.step == AddNodeStep.Form
    val missing = state.error == AddNodeError.MISSING_FIELDS
    val required = stringResource(R.string.error_required)
    fun requiredError(blank: Boolean) = if (missing && blank) required else null
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        AppTopBar(
            title = stringResource(if (state.needsSiteName) R.string.add_site else R.string.add_node),
            onBack = actions.onBack,
            actions = {
                if (state.step == AddNodeStep.Working) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
                }
            },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
        ) {
            if (state.needsSiteName) {
                UnderlineField(
                    stringResource(R.string.site_name),
                    state.siteName,
                    actions.onSiteName,
                    enabled = editable,
                    error = requiredError(state.siteName.isBlank()),
                )
            }
            UnderlineField(
                stringResource(R.string.node_label),
                state.label,
                actions.onLabel,
                enabled = editable,
                error = requiredError(state.label.isBlank()),
            )
            UnderlineField(
                stringResource(R.string.node_url),
                state.url,
                actions.onUrl,
                enabled = editable,
                error = requiredError(state.url.isBlank())
                    ?: if (state.error == AddNodeError.INVALID_URL) stringResource(R.string.error_invalid_url) else null,
                placeholder = "192.168.1.1",
                keyboardType = KeyboardType.Uri,
            )
            UnderlineField(
                stringResource(R.string.username),
                state.username,
                actions.onUsername,
                enabled = editable,
                error = requiredError(state.username.isBlank()),
            )
            UnderlineField(
                stringResource(R.string.password),
                state.password,
                actions.onPassword,
                enabled = editable,
                error = requiredError(state.password.isEmpty()),
                password = true,
            )
            alertTitle(state.error)?.let { title ->
                AlertCard(
                    title,
                    if (state.error == AddNodeError.UNREACHABLE) state.errorDetail else null,
                    Modifier.padding(top = 20.dp),
                )
            }
        }
        PrimaryButton(
            stringResource(R.string.connect),
            actions.onSubmit,
            Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 16.dp),
            enabled = editable,
        )
    }
    (state.step as? AddNodeStep.ConfirmCertificate)?.let { step ->
        AlertDialog(
            onDismissRequest = actions.onReject,
            title = { Text(stringResource(R.string.certificate_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.certificate_text))
                    Text(
                        step.fingerprint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = actions.onTrust) { Text(stringResource(R.string.trust)) } },
            dismissButton = { TextButton(onClick = actions.onReject) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Errors that are not about a single field, shown in a card under the form. */
@Composable
private fun alertTitle(error: AddNodeError?): String? = when (error) {
    null, AddNodeError.MISSING_FIELDS, AddNodeError.INVALID_URL -> null
    AddNodeError.LOGIN_FAILED -> stringResource(R.string.error_login_failed)
    AddNodeError.UNREACHABLE -> stringResource(R.string.add_node_unreachable)
    AddNodeError.CERTIFICATE_REJECTED -> stringResource(R.string.error_certificate_rejected)
    AddNodeError.SITE_NOT_FOUND -> stringResource(R.string.error_site_not_found)
}
