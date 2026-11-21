package wrthub.app.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import wrthub.app.data.NodeConfig
import wrthub.app.domain.AdminFailure
import wrthub.app.domain.NodeAdmin
import wrthub.app.domain.SiteSession
import wrthub.app.domain.adminCall
import wrthub.app.domain.adminFailure
import wrthub.app.domain.awaitNode
import wrthub.core.admin.PackageInfo
import wrthub.core.admin.PackageResult
import wrthub.core.admin.Storage

enum class PackageTab { INSTALLED, AVAILABLE }

enum class PackageAction { INSTALL, REMOVE, UPDATE_LISTS }

/** What apk printed for one action, shown until dismissed. */
data class PackageOutcome(val action: PackageAction, val name: String?, val result: PackageResult)

data class PackagesUiState(
    val title: String = "",
    val tab: PackageTab = PackageTab.INSTALLED,
    val filter: String = "",
    val loading: Boolean = false,
    val failure: AdminFailure? = null,
    /** Filtered; null until loaded. */
    val packages: List<PackageInfo>? = null,
    val installedNames: Set<String> = emptySet(),
    val storage: Storage? = null,
    val busy: Boolean = false,
    val outcome: PackageOutcome? = null,
    val notice: AdminNotice? = null,
)

/** Installed and available packages of one node. Lists are large, so the available one loads only when opened. */
class PackagesViewModel(
    private val nodeId: String,
    private val session: SiteSession,
    private val adminFor: (NodeConfig) -> NodeAdmin,
) : ViewModel() {
    private data class Local(
        val tab: PackageTab = PackageTab.INSTALLED,
        val filter: String = "",
        val loading: Boolean = false,
        val failure: AdminFailure? = null,
        val installed: List<PackageInfo>? = null,
        val available: List<PackageInfo>? = null,
        val storage: Storage? = null,
        val busy: Boolean = false,
        val outcome: PackageOutcome? = null,
        val notice: AdminNotice? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<PackagesUiState> = combine(session.state, local) { site, ui ->
        val query = ui.filter.trim()
        val source = if (ui.tab == PackageTab.INSTALLED) ui.installed else ui.available
        PackagesUiState(
            title = site.nodes.firstOrNull { it.config.id == nodeId }?.config?.label.orEmpty(),
            tab = ui.tab,
            filter = ui.filter,
            loading = ui.loading,
            failure = ui.failure,
            packages = source?.filter { query.isEmpty() || query in it.name || it.description?.contains(query, ignoreCase = true) == true },
            installedNames = ui.installed.orEmpty().map { it.name }.toSet(),
            storage = ui.storage,
            busy = ui.busy,
            outcome = ui.outcome,
            notice = ui.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PackagesUiState())

    init {
        viewModelScope.launch {
            session.awaitNode(nodeId)
            load(PackageTab.INSTALLED)
        }
    }

    fun selectTab(tab: PackageTab) {
        local.update { it.copy(tab = tab, failure = null) }
        val loaded = if (tab == PackageTab.INSTALLED) local.value.installed else local.value.available
        if (loaded == null) load(tab)
    }

    fun setFilter(value: String) = local.update { it.copy(filter = value) }

    fun refresh() = load(local.value.tab)

    fun install(name: String) = run(PackageAction.INSTALL, name) { it.installPackage(name) }

    fun remove(name: String) = run(PackageAction.REMOVE, name) { it.removePackage(name) }

    fun updateLists() = run(PackageAction.UPDATE_LISTS, null) { it.updatePackageLists() }

    fun dismissOutcome() = local.update { it.copy(outcome = null) }

    fun dismissNotice() = local.update { it.copy(notice = null) }

    private fun admin(): NodeAdmin? = session.state.value.nodes.firstOrNull { it.config.id == nodeId }?.config?.let(adminFor)

    /** apk's own refusals come back as a non-zero code and are shown with its output; transport failures as notices. */
    private fun run(action: PackageAction, name: String?, call: suspend (NodeAdmin) -> PackageResult) {
        val admin = admin() ?: return
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = adminCall { call(admin) }
            local.update { ui ->
                ui.copy(
                    busy = false,
                    outcome = result.getOrNull()?.let { PackageOutcome(action, name, it) },
                    notice = result.exceptionOrNull()?.let { AdminNotice.Failed(it.adminFailure) },
                    // Stale after any change: reloaded on the next visit of the tab.
                    available = if (action == PackageAction.UPDATE_LISTS) null else ui.available,
                )
            }
            if (result.isSuccess) {
                if (action == PackageAction.UPDATE_LISTS) load(PackageTab.AVAILABLE) else load(PackageTab.INSTALLED)
            }
        }
    }

    private fun load(tab: PackageTab) {
        val admin = admin() ?: return
        local.update { it.copy(loading = true, failure = null) }
        viewModelScope.launch {
            val result = adminCall {
                val packages = if (tab == PackageTab.INSTALLED) admin.installedPackages() else admin.availablePackages()
                packages to admin.storage()
            }
            local.update { ui ->
                result.fold(
                    { (packages, storage) ->
                        if (tab == PackageTab.INSTALLED) {
                            ui.copy(loading = false, installed = packages, storage = storage)
                        } else {
                            ui.copy(loading = false, available = packages, storage = storage)
                        }
                    },
                    { ui.copy(loading = false, failure = it.adminFailure) },
                )
            }
        }
    }
}
