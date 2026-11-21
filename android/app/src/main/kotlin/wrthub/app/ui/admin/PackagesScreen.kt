package wrthub.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wrthub.app.R
import wrthub.app.ui.formatBytes
import wrthub.app.ui.theme.LocalSemanticColors
import wrthub.app.ui.TrailingText
import wrthub.core.admin.PackageInfo
import wrthub.core.admin.isEssentialPackage

data class PackagesActions(
    val onTab: (PackageTab) -> Unit,
    val onFilter: (String) -> Unit,
    val onInstall: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onUpdateLists: () -> Unit,
    val onDismissOutcome: () -> Unit,
    val onRefresh: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onBack: () -> Unit,
)

@Composable
fun PackagesRoute(viewModel: PackagesViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PackagesScreen(
        state,
        PackagesActions(
            viewModel::selectTab,
            viewModel::setFilter,
            viewModel::install,
            viewModel::remove,
            viewModel::updateLists,
            viewModel::dismissOutcome,
            viewModel::refresh,
            viewModel::dismissNotice,
            onBack,
        ),
    )
}

/** Confirmation pending for an action; [pkg] is null for a list update. */
private data class Pending(val action: PackageAction, val pkg: PackageInfo?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagesScreen(state: PackagesUiState, actions: PackagesActions) {
    var selected by remember { mutableStateOf<PackageInfo?>(null) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    AdminScaffold(
        title = stringResource(R.string.packages_title, state.title),
        notice = state.notice,
        onDismissNotice = actions.onDismissNotice,
        onBack = actions.onBack,
        onRefresh = actions.onRefresh,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                Tab(state.tab == PackageTab.INSTALLED, { actions.onTab(PackageTab.INSTALLED) }, text = { Text(stringResource(R.string.packages_installed)) })
                Tab(state.tab == PackageTab.AVAILABLE, { actions.onTab(PackageTab.AVAILABLE) }, text = { Text(stringResource(R.string.packages_available)) })
            }
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.storage?.let {
                    Text(
                        stringResource(R.string.storage_free, it.mount, formatBytes(it.availableBytes), formatBytes(it.sizeBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.filter,
                        onValueChange = actions.onFilter,
                        label = { Text(stringResource(R.string.filter)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.tab == PackageTab.AVAILABLE) {
                    OutlinedButton(onClick = { pending = Pending(PackageAction.UPDATE_LISTS, null) }, enabled = !state.busy) {
                        Text(stringResource(R.string.packages_update_lists))
                    }
                }
                state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
            }
            state.packages?.let { packages ->
                Text(
                    stringResource(R.string.packages_count, packages.size),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(packages, key = { it.name }) { pkg ->
                        PackageRow(pkg, installed = state.tab == PackageTab.AVAILABLE && pkg.name in state.installedNames) { selected = pkg }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    selected?.let { pkg ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            PackageDetail(pkg, installed = pkg.name in state.installedNames, busy = state.busy) { action ->
                selected = null
                pending = Pending(action, pkg)
            }
        }
    }
    pending?.let { p -> ConfirmDialog(p, state, actions) { pending = null } }
    state.outcome?.let { OutcomeDialog(it, actions.onDismissOutcome) }
}

@Composable
private fun PackageRow(pkg: PackageInfo, installed: Boolean, onClick: () -> Unit) {
    val semantic = LocalSemanticColors.current
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        supportingContent = { Text(listOfNotNull(pkg.version, pkg.installedSize?.let(::formatBytes)).joinToString(" · ")) },
        trailingContent = { if (installed) TrailingText(stringResource(R.string.packages_installed_mark), semantic.signalGood) },
    ) { Text(pkg.name) }
}

@Composable
private fun PackageDetail(pkg: PackageInfo, installed: Boolean, busy: Boolean, onAction: (PackageAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pkg.name, style = MaterialTheme.typography.titleLarge)
        Text(pkg.version, color = MaterialTheme.colorScheme.onSurfaceVariant)
        pkg.description?.let { Text(it) }
        pkg.installedSize?.let { Text(stringResource(R.string.packages_size, formatBytes(it))) }
        if (pkg.depends.isNotEmpty()) Text(stringResource(R.string.packages_depends, pkg.depends.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
        when {
            !installed -> Button(onClick = { onAction(PackageAction.INSTALL) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Text(stringResource(R.string.packages_install))
            }
            isEssentialPackage(pkg.name) -> Text(
                stringResource(R.string.packages_essential),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 16.dp),
            )
            else -> Button(
                onClick = { onAction(PackageAction.REMOVE) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            ) { Text(stringResource(R.string.packages_remove)) }
        }
    }
}

@Composable
private fun ConfirmDialog(pending: Pending, state: PackagesUiState, actions: PackagesActions, onClose: () -> Unit) {
    val pkg = pending.pkg
    val title = when (pending.action) {
        PackageAction.INSTALL -> stringResource(R.string.packages_install_confirm, pkg?.name.orEmpty())
        PackageAction.REMOVE -> stringResource(R.string.packages_remove_confirm, pkg?.name.orEmpty())
        PackageAction.UPDATE_LISTS -> stringResource(R.string.packages_update_lists)
    }
    val size = pkg?.installedSize?.takeIf { pending.action == PackageAction.INSTALL }
    val free = state.storage?.availableBytes
    val tooBig = size != null && free != null && size > free
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (size != null && free != null) {
                    Text(stringResource(R.string.packages_space, formatBytes(size), formatBytes(free)))
                }
                if (tooBig) Text(stringResource(R.string.packages_too_big), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.packages_no_undo), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                when (pending.action) {
                    PackageAction.INSTALL -> actions.onInstall(pkg!!.name)
                    PackageAction.REMOVE -> actions.onRemove(pkg!!.name)
                    PackageAction.UPDATE_LISTS -> actions.onUpdateLists()
                }
            }) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun OutcomeDialog(outcome: PackageOutcome, onDismiss: () -> Unit) {
    val ok = outcome.result.code == 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (ok) R.string.packages_done else R.string.packages_failed, outcome.name ?: stringResource(R.string.packages_lists))) },
        text = {
            val output = listOf(outcome.result.stdout, outcome.result.stderr).filter { it.isNotBlank() }.joinToString("\n")
            Text(
                output.ifEmpty { "—" },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
