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
    fun `an endpoint is registered once per server, and a new one replaces it everywhere`() = runTest {
        val sites = listOf(
            SiteConfig("a", "A", hub = link(server, "cabin")),
            SiteConfig("b", "B", hub = link(server, "office")),
            SiteConfig("c", "C", hub = link(other, "home")),
            SiteConfig("d", "Direct"),
        )
        val push = registry(sites)
        ok(server)
        ok(other)
        assertEquals(emptyList(), push.onNewEndpoint("https://push.example/1"))
        assertEquals(1, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
        assertEquals(1, other.requestCount)
        other.takeRequest()

        ok(server)
        ok(server)
        ok(other)
        ok(other)
        push.onNewEndpoint("https://push.example/2")
        val unregister = server.takeRequest()
        assertEquals("DELETE", unregister.method)
        assertEquals("""{"endpoint":"https://push.example/1"}""", unregister.body?.utf8())
        assertEquals("""{"endpoint":"https://push.example/2"}""", server.takeRequest().body?.utf8())
        assertEquals("https://push.example/2", push.endpoint())
    }

    @Test
    fun `a server that refuses is reported and the others still register`() = runTest {
        val push = registry(listOf(SiteConfig("a", "A", hub = link(server, "s")), SiteConfig("c", "C", hub = link(other, "s"))))
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"https endpoints only"}""").build())
        ok(other)
        val failures = push.onNewEndpoint("https://push.example/1")
        assertEquals(1, failures.size)
        assertEquals(1, other.requestCount)
    }

    @Test
    fun `unregistering forgets the endpoint`() = runTest {
        val push = registry(listOf(SiteConfig("a", "A", hub = link(server, "s"))))
        ok(server)
        ok(server)
        push.onNewEndpoint("https://push.example/1")
        push.onUnregistered()
        assertNull(push.endpoint())
        server.takeRequest()
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `notifications parse as the server sends them and anything else is dropped`() {
        val body = """{"kind":"new_client","siteId":"s","siteName":"Cabin","nodeId":"r","nodeLabel":"Router","macs":["ce:11:22:00:00:01"],"at":5}"""
        assertEquals(HubNotification("new_client", "s", "Cabin", "r", "Router", listOf("ce:11:22:00:00:01"), 5), parseHubNotification(body))
        assertEquals(
            HubNotification("wan_down", "s", "Cabin", null, null, emptyList(), 5),
            parseHubNotification("""{"kind":"wan_down","siteId":"s","siteName":"Cabin","nodeId":null,"nodeLabel":null,"at":5}"""),
        )
        assertNull(parseHubNotification("hello"))
        assertNull(parseHubNotification("""{"kind":"x"}"""))
    }
}
