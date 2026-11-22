package wrthub.app.data

import kotlinx.coroutines.flow.Flow

interface SiteRepository {
    val sites: Flow<List<SiteConfig>>
    val selectedSiteId: Flow<String?>

    suspend fun upsertSite(site: SiteConfig)

    suspend fun removeSite(id: String)

    suspend fun removeNode(siteId: String, nodeId: String)

    suspend fun setUnmanagedAp(siteId: String, mac: String, isAp: Boolean)

    suspend fun select(id: String)
}
