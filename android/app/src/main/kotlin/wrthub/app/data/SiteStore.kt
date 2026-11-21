package wrthub.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SiteStore(private val dataStore: DataStore<Preferences>) : SiteRepository {
    override val sites: Flow<List<SiteConfig>> = dataStore.data.map { decodeSites(it[SITES]) }

    override val selectedSiteId: Flow<String?> = dataStore.data.map { resolveSelected(decodeSites(it[SITES]), it[SELECTED]) }

    override suspend fun upsertSite(site: SiteConfig) = editSites { upserted(it, site) }

    override suspend fun removeSite(id: String) = editSites { sites -> sites.filterNot { it.id == id } }

    override suspend fun removeNode(siteId: String, nodeId: String) = editSites { withoutNode(it, siteId, nodeId) }

    override suspend fun select(id: String) {
        dataStore.edit { it[SELECTED] = id }
    }

    private suspend fun editSites(transform: (List<SiteConfig>) -> List<SiteConfig>) {
        dataStore.edit { prefs -> prefs[SITES] = encodeSites(transform(decodeSites(prefs[SITES]))) }
    }

    private companion object {
        val SITES = stringPreferencesKey("sites")
        val SELECTED = stringPreferencesKey("selected_site")
    }
}
