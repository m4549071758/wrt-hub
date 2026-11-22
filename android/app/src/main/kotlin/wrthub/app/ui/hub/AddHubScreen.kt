package wrthub.app.ui.hub

import android.app.Activity
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.IOException
import kotlinx.coroutines.launch
import wrthub.app.R
import wrthub.app.data.HubSignIn
import wrthub.app.ui.design.AlertCard
import wrthub.app.ui.design.AppCheck
import wrthub.app.ui.design.AppRow
import wrthub.app.ui.design.AppTopBar
import wrthub.app.ui.design.GroupDivider
import wrthub.app.ui.design.PrimaryButton
import wrthub.app.ui.design.RowGroup
import wrthub.app.ui.design.ScreenPadding
import wrthub.app.ui.design.SecondaryButton
import wrthub.app.ui.design.SubtitleText
import wrthub.app.ui.design.UnderlineField

data class AddHubActions(
    val onServer: (String) -> Unit,
    val onCode: (String) -> Unit,
    val onPair: () -> Unit,
    val onSignIn: () -> Unit,
    /** Null when this phone has no Google Play services code scanner. */
    val onScan: (() -> Unit)?,
    val onToggle: (String) -> Unit,
    val onSave: () -> Unit,
    val onBack: () -> Unit,
)

private fun scannerAvailable(context: Context) =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

@Composable
fun AddHubRoute(viewModel: AddHubViewModel, signIn: HubSignIn, onDone: () -> Unit, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val canScan = remember { scannerAvailable(context) }
    val signInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            viewModel.onSignInFailed(null)
        } else {
            scope.launch {
                try {
                    viewModel.onSignedIn(signIn.finish(data))
                } catch (e: IOException) {
                    viewModel.onSignInFailed(e.message)
                }
            }
        }
    }
    LaunchedEffect(state.step) {
        when (val step = state.step) {
            AddHubStep.Done -> onDone()
            is AddHubStep.SigningIn -> try {
                signInLauncher.launch(signIn.intent(step.oidc))
            } catch (e: IOException) {
                viewModel.onSignInFailed(e.message)
            }
            else -> Unit
        }
    }
    val back = { if (state.step is AddHubStep.ChooseSites) viewModel.backToForm() else onBack() }
    AddHubScreen(
        state,
        AddHubActions(
            onServer = viewModel::onServer,
            onCode = viewModel::onCode,
            onPair = viewModel::pair,
            onSignIn = viewModel::signIn,
            onScan = if (!canScan) {
                null
            } else {
                {
                    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    GmsBarcodeScanning.getClient(context, options).startScan()
                        .addOnSuccessListener { barcode -> barcode.rawValue?.let(viewModel::onScanned) ?: viewModel.onScanFailed(null) }
                        .addOnFailureListener { e -> viewModel.onScanFailed(e.message) }
                }
            },
            onToggle = viewModel::toggle,
            onSave = viewModel::save,
            onBack = back,
        ),
    )
}

@Composable
fun AddHubScreen(state: AddHubUiState, actions: AddHubActions) {
    val choosing = state.step as? AddHubStep.ChooseSites
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        AppTopBar(
            title = stringResource(if (choosing != null) R.string.hub_choose_sites else R.string.add_hub_site),
            onBack = actions.onBack,
            actions = {
                if (state.step == AddHubStep.Working || state.step is AddHubStep.SigningIn) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
                }
            },
        )
        if (choosing != null) ChooseSites(choosing, state.chosen, actions) else HubForm(state, actions)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.HubForm(state: AddHubUiState, actions: AddHubActions) {
    val editable = state.step == AddHubStep.Form
    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
    ) {
        Text(
            stringResource(R.string.add_hub_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        UnderlineField(
            stringResource(R.string.hub_server),
            state.server,
            actions.onServer,
            enabled = editable,
            error = when (state.error) {
                AddHubError.MISSING_SERVER -> stringResource(R.string.error_required)
                AddHubError.INVALID_URL -> stringResource(R.string.error_invalid_url)
                else -> null
            },
            placeholder = "hub.example.com",
            keyboardType = KeyboardType.Uri,
        )
        UnderlineField(
            stringResource(R.string.hub_pairing_code),
            state.code,
            actions.onCode,
            enabled = editable,
            error = if (state.error == AddHubError.MISSING_CODE) stringResource(R.string.error_required) else null,
            placeholder = "ABCD-EFGH-JKLM",
        )
        alertTitle(state.error)?.let { title ->
            AlertCard(title, state.errorDetail, Modifier.padding(top = 20.dp))
        }
    }
    Column(Modifier.padding(horizontal = ScreenPadding, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            actions.onScan?.let { scan -> SecondaryButton(stringResource(R.string.hub_scan_qr), scan, Modifier.weight(1f), enabled = editable) }
            SecondaryButton(stringResource(R.string.hub_sign_in), actions.onSignIn, Modifier.weight(1f), enabled = editable)
        }
        PrimaryButton(stringResource(R.string.hub_pair), actions.onPair, Modifier.fillMaxWidth(), enabled = editable)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ChooseSites(step: AddHubStep.ChooseSites, chosen: Set<String>, actions: AddHubActions) {
    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
    ) {
        RowGroup {
            step.sites.forEachIndexed { index, site ->
                if (index > 0) GroupDivider()
                AppRow(
                    site.name,
                    icon = R.drawable.ic_hub,
                    subtitleContent = { SubtitleText(pluralStringResource(R.plurals.node_count, site.nodes.size, site.nodes.size)) },
                    trailing = { AppCheck(site.id in chosen) },
                    onClick = { actions.onToggle(site.id) },
                    chevron = false,
                )
            }
        }
    }
    PrimaryButton(
        pluralStringResource(R.plurals.hub_add_sites, chosen.size, chosen.size),
        actions.onSave,
        Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 16.dp),
        enabled = chosen.isNotEmpty(),
    )
}

@Composable
private fun alertTitle(error: AddHubError?): String? = when (error) {
    null, AddHubError.MISSING_SERVER, AddHubError.MISSING_CODE, AddHubError.INVALID_URL -> null
    AddHubError.INVALID_QR -> stringResource(R.string.hub_error_qr)
    AddHubError.UNREACHABLE -> stringResource(R.string.hub_error_unreachable)
    AddHubError.PAIRING_REJECTED -> stringResource(R.string.hub_error_pairing)
    AddHubError.NO_OIDC -> stringResource(R.string.hub_error_no_oidc)
    AddHubError.SIGN_IN_FAILED -> stringResource(R.string.hub_error_sign_in)
    AddHubError.NO_SITES -> stringResource(R.string.hub_error_no_sites)
}
