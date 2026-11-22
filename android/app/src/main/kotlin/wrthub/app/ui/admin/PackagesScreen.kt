package wrthub.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import wrthub.app.ui.design.AppRow
import wrthub.app.ui.design.Badge
import wrthub.app.ui.design.KeyValueCard
import wrthub.app.ui.design.ListPadding
import wrthub.app.ui.design.PrimaryButton
import wrthub.app.ui.design.ScreenPadding
import wrthub.app.ui.design.SecondaryButton
import wrthub.app.ui.design.SectionHeader
import wrthub.app.ui.design.SegmentedToggle
import wrthub.app.ui.design.UnderlineField
import wrthub.app.ui.design.ValueText
import wrthub.app.ui.formatBytes
import wrthub.app.ui.theme.weight
import androidx.compose.ui.text.font.FontWeight
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
        Column(Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    SegmentedToggle(
                        listOf(PackageTab.INSTALLED to stringResource(R.string.packages_installed), PackageTab.AVAILABLE to stringResource(R.string.packages_available)),
                        state.tab,
                        actions.onTab,
                        Modifier.padding(vertical = 4.dp),
                    )
                }
                state.storage?.let {
                    item {
                        KeyValueCard(
                            listOf(it.mount to { ValueText("${formatBytes(it.availableBytes)} / ${formatBytes(it.sizeBytes)}") }),
                            Modifier.padding(top = 6.dp),
                        )
                    }
                }
                item { UnderlineField(stringResource(R.string.filter), state.filter, actions.onFilter, Modifier.fillMaxWidth().padding(top = 6.dp)) }
                if (state.tab == PackageTab.AVAILABLE) {
                    item {
                        SecondaryButton(
                            stringResource(R.string.packages_update_lists),
                            { pending = Pending(PackageAction.UPDATE_LISTS, null) },
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            enabled = !state.busy,
                            height = 44,
                        )
                    }
                }
                state.failure?.let { item { Text(failureText(it), color = MaterialTheme.colorScheme.error) } }
                state.packages?.let { packages ->
                    item { SectionHeader(stringResource(R.string.packages_count, packages.size)) }
                    items(packages, key = { it.name }) { pkg ->
                        PackageRow(pkg, installed = state.tab == PackageTab.AVAILABLE && pkg.name in state.installedNames) { selected = pkg }
                    }
                }
            }
        }
    }
    selected?.let { pkg ->
        ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = MaterialTheme.colorScheme.background) {
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
    AppRow(
        pkg.name,
        subtitle = listOfNotNull(pkg.version, pkg.installedSize?.let(::formatBytes)).joinToString(" · "),
        trailing = if (installed) ({ Badge(stringResource(R.string.packages_installed_mark)) }) else null,
        onClick = onClick,
        standalone = true,
    )
}

@Composable
private fun PackageDetail(pkg: PackageInfo, installed: Boolean, busy: Boolean, onAction: (PackageAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pkg.name, style = MaterialTheme.typography.titleLarge.weight(FontWeight.SemiBold))
        Text(pkg.version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        pkg.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        val rows = buildList<Pair<String, @Composable () -> Unit>> {
            pkg.installedSize?.let { add(stringResource(R.string.packages_size_label) to { ValueText(formatBytes(it)) }) }
            if (pkg.depends.isNotEmpty()) add(stringResource(R.string.packages_depends_label) to { ValueText(pkg.depends.joinToString(", ")) })
        }
        if (rows.isNotEmpty()) KeyValueCard(rows)
        when {
            !installed -> PrimaryButton(stringResource(R.string.packages_install), { onAction(PackageAction.INSTALL) }, Modifier.fillMaxWidth().padding(vertical = 16.dp), enabled = !busy)
            isEssentialPackage(pkg.name) -> Badge(stringResource(R.string.packages_required), Modifier.padding(vertical = 16.dp))
            else -> SecondaryButton(
                stringResource(R.string.packages_remove),
                { onAction(PackageAction.REMOVE) },
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                enabled = !busy,
            )
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
