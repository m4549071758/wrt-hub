package wrthub.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import wrthub.app.data.HubAuth
import wrthub.app.data.HubConnections
import wrthub.app.data.HubLink
import wrthub.app.data.SiteConfig
import wrthub.app.push.HubNotification
import wrthub.app.push.PushRegistry
import wrthub.app.push.parseHubNotification

class PushRegistryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val other = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeTest
    fun start() {
        server.start()
        other.start()
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        server.close()
        other.close()
    }

    private fun link(server: MockWebServer, siteId: String) = HubLink(server.url("/").toString(), siteId, HubAuth.DEVICE, "t")

    private fun registry(sites: List<SiteConfig>) = PushRegistry(
        FakeSiteRepository(sites),
        HubConnections(OkHttpClient()) { it.credentialCipher },
        PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "push.preferences_pb") },
    )

    private fun ok(target: MockWebServer) = target.enqueue(MockResponse.Builder().code(204).build())

    @Test
    fun `an installation ID is registered once per server, and a new one replaces it everywhere`() = runTest {
        val sites = listOf(
            SiteConfig("a", "A", hub = link(server, "cabin")),
            SiteConfig("b", "B", hub = link(server, "office")),
            SiteConfig("c", "C", hub = link(other, "home")),
            SiteConfig("d", "Direct"),
        )
        val push = registry(sites)
        ok(server)
        ok(other)
        assertEquals(emptyList(), push.onRegistered("fid-1"))
        assertEquals(1, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
        assertEquals(1, other.requestCount)
        other.takeRequest()

        ok(server)
        ok(server)
        ok(other)
        ok(other)
        push.onRegistered("fid-2")
        val unregister = server.takeRequest()
        assertEquals("DELETE", unregister.method)
        assertEquals("""{"token":"fid-1"}""", unregister.body?.utf8())
        assertEquals("""{"token":"fid-2"}""", server.takeRequest().body?.utf8())
        assertEquals("fid-2", push.installationId())
    }

    @Test
    fun `a server that refuses is reported and the others still register`() = runTest {
        val push = registry(listOf(SiteConfig("a", "A", hub = link(server, "s")), SiteConfig("c", "C", hub = link(other, "s"))))
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
        ok(other)
        val failures = push.onRegistered("fid-1")
        assertEquals(1, failures.size)
        assertEquals(1, other.requestCount)
    }

    @Test
    fun `notifications parse from the data the server sends and anything else is dropped`() {
        val data = mapOf("kind" to "new_client", "siteId" to "s", "siteName" to "Cabin", "nodeId" to "r", "nodeLabel" to "Router", "macs" to "ce:11:22:00:00:01,ce:11:22:00:00:02", "at" to "5")
        assertEquals(
            HubNotification("new_client", "s", "Cabin", "r", "Router", listOf("ce:11:22:00:00:01", "ce:11:22:00:00:02"), 5),
            parseHubNotification(data),
        )
        assertEquals(
            HubNotification("wan_down", "s", "Cabin", null, null, emptyList(), 5),
            parseHubNotification(mapOf("kind" to "wan_down", "siteId" to "s", "siteName" to "Cabin", "at" to "5")),
        )
        assertNull(parseHubNotification(mapOf("kind" to "x")))
        assertNull(parseHubNotification(mapOf("kind" to "wan_down", "siteId" to "s", "siteName" to "Cabin", "at" to "soon")))
    }
}
