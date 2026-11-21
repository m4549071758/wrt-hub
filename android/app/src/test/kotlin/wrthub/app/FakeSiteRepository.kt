package wrthub.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import wrthub.app.data.NodeConfig
import wrthub.app.data.SiteConfig
import wrthub.app.data.SiteRepository
import wrthub.app.data.resolveSelected
import wrthub.app.data.upserted
import wrthub.app.data.withoutNode

/**
 * A scope for app-wide objects such as SiteSession: it runs on the test scheduler so advanceUntilIdle drives it
 * (work in backgroundScope is skipped by advanceUntilIdle), and runTest does not wait for its endless collectors.
 */
fun TestScope.appScope(): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

class FakeSiteRepository(sites: List<SiteConfig> = emptyList(), selected: String? = null) : SiteRepository {
    private val sitesFlow = MutableStateFlow(sites)
    private val selectedFlow = MutableStateFlow(selected)

    val current: List<SiteConfig> get() = sitesFlow.value
    val currentSelection: String? get() = resolveSelected(sitesFlow.value, selectedFlow.value)

    override val sites: Flow<List<SiteConfig>> = sitesFlow
    override val selectedSiteId: Flow<String?> = combine(sitesFlow, selectedFlow, ::resolveSelected)

    override suspend fun upsertSite(site: SiteConfig) = sitesFlow.update { upserted(it, site) }

    override suspend fun removeSite(id: String) = sitesFlow.update { list -> list.filterNot { it.id == id } }

    override suspend fun removeNode(siteId: String, nodeId: String) = sitesFlow.update { withoutNode(it, siteId, nodeId) }

    override suspend fun select(id: String) {
        selectedFlow.value = id
    }
}

fun node(id: String, label: String = id) = NodeConfig(id, label, "http://$id.example", "root", "enc:pw")
