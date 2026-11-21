package wrthub.app.ui.sites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNodeScreen(state: AddNodeUiState, actions: AddNodeActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.needsSiteName) R.string.add_site else R.string.add_node)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                },
            )
        },
    ) { padding ->
        val editable = state.step == AddNodeStep.Form
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.needsSiteName) {
                Field(state.siteName, actions.onSiteName, R.string.site_name, editable)
            }
            Field(state.label, actions.onLabel, R.string.node_label, editable)
            Field(state.url, actions.onUrl, R.string.node_url, editable, KeyboardOptions(keyboardType = KeyboardType.Uri), placeholder = "192.168.1.1")
            Field(state.username, actions.onUsername, R.string.username, editable)
            val password = rememberTextFieldState(state.password)
            LaunchedEffect(password, actions.onPassword) {
                snapshotFlow { password.text.toString() }.collect(actions.onPassword)
            }
            OutlinedSecureTextField(
                state = password,
                label = { Text(stringResource(R.string.password)) },
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let { Text(errorText(it, state.errorDetail), color = MaterialTheme.colorScheme.error) }
            Button(onClick = actions.onSubmit, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                if (state.step == AddNodeStep.Working) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.connect))
                }
            }
        }
    }
    (state.step as? AddNodeStep.ConfirmCertificate)?.let { step ->
        AlertDialog(
            onDismissRequest = actions.onReject,
            title = { Text(stringResource(R.string.certificate_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.certificate_body))
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

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun errorText(error: AddNodeError, detail: String?): String = when (error) {
    AddNodeError.MISSING_FIELDS -> stringResource(R.string.error_missing_fields)
    AddNodeError.INVALID_URL -> stringResource(R.string.error_invalid_url)
    AddNodeError.LOGIN_FAILED -> stringResource(R.string.error_login_failed)
    AddNodeError.UNREACHABLE -> stringResource(R.string.error_unreachable, detail.orEmpty())
    AddNodeError.CERTIFICATE_REJECTED -> stringResource(R.string.error_certificate_rejected)
    AddNodeError.SITE_NOT_FOUND -> stringResource(R.string.error_site_not_found)
}
