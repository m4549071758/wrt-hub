package wrthub.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import wrthub.app.data.SiteConfig
import wrthub.app.data.SiteStore

class SiteStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val siteA = SiteConfig("a", "Home", listOf(node("n1")))
    private val siteB = SiteConfig("b", "Cabin", listOf(node("n2"), node("n3")))

    private fun <T> withStore(block: suspend (SiteStore) -> T): T = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "sites.preferences_pb") }
            block(SiteStore(dataStore))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `sites round trip and the selection falls back to the first site`() = withStore { store ->
        store.upsertSite(siteA)
        store.upsertSite(siteB)
        assertEquals(listOf(siteA, siteB), store.sites.first())
        assertEquals("a", store.selectedSiteId.first())
        store.select("b")
        assertEquals("b", store.selectedSiteId.first())
        store.removeSite("b")
        assertEquals("a", store.selectedSiteId.first())
        store.upsertSite(siteA.copy(name = "Renamed"))
        assertEquals("Renamed", store.sites.first().single().name)
        store.removeNode("a", "n1")
        assertTrue(store.sites.first().single().nodes.isEmpty())
    }

    @Test
    fun `saved sites survive a new store on the same file`() {
        withStore { it.upsertSite(siteB) }
        assertEquals(listOf(siteB), withStore { it.sites.first() })
    }
}
