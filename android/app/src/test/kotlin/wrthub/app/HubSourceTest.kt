package wrthub.app

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import wrthub.app.data.HubAuth
import wrthub.app.data.HubLink
import wrthub.app.data.SiteConfig
import wrthub.app.domain.HubCounterReader
import wrthub.app.domain.HubNodeOperations
import wrthub.app.domain.HubSiteLoader
import wrthub.app.domain.NodeState
import wrthub.app.domain.SiteLoader
import wrthub.app.domain.hubNodeConfig
import wrthub.core.Fixtures
import wrthub.core.hub.HubClient
import wrthub.core.hub.HubUbusCaller
import wrthub.core.model.DeviceCounters
import wrthub.core.snapshot.encodeSnapshot

class HubSourceTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private val link by lazy { HubLink(server.url("/").toString(), "cabin", HubAuth.DEVICE, "enc") }
    private val hub by lazy { HubClient(link.serverUrl, OkHttpClient()) { "t0ken" } }
    private val site by lazy { SiteConfig("local-cabin", "Cabin", listOf(hubNodeConfig(link, "router", "Router")), hub = link) }

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun snapshotJson(id: String) = encodeSnapshot(Fixtures.meshSite.getValue(id))

    @Test
    fun `node states follow the server's view of each node`() = runTest {
        respond(
            """{"serverTime":9,"nodes":[
              {"id":"router","label":"Router","state":"fresh","receivedAt":8,"snapshot":${snapshotJson("router")}},
              {"id":"ap","label":"Hall AP","state":"stale","receivedAt":1,"snapshot":${snapshotJson("ap")}},
              {"id":"shed","label":"Shed","state":"offline","receivedAt":1,"snapshot":${snapshotJson("ap")}},
              {"id":"new","label":"New","state":"never"}]}""",
        )
        val nodes = SiteLoader(HubSiteLoader { hub }) { error("no direct calls for a hub site") }.load(site)

        assertEquals(listOf("router", "ap", "shed", "new"), nodes.map { it.config.id })
        assertEquals("Hall AP", nodes[1].config.label)
        assertEquals(Fixtures.meshSite.getValue("router"), assertIs<NodeState.Online>(nodes[0]).snapshot)
        assertIs<NodeState.Online>(nodes[1])
        assertIs<NodeState.Unreachable>(nodes[2])
        assertIs<NodeState.Unreachable>(nodes[3])
        assertEquals("/app/v1/sites/cabin/snapshots", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `an unreachable server leaves the stored nodes unreachable`() = runTest {
        respond("""{"error":"unauthorized"}""", code = 401)
        val nodes = SiteLoader(HubSiteLoader { hub }) { error("no direct calls for a hub site") }.load(site)
        assertEquals(site.nodes, nodes.map { it.config })
        assertIs<NodeState.Unreachable>(nodes.single())
    }

    @Test
    fun `stream decodes pushed snapshots`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .body("event: snapshot\ndata: {\"nodeId\":\"router\",\"receivedAt\":5,\"serverTime\":5,\"snapshot\":${snapshotJson("router")}}\n\n")
                .build(),
        )
        val update = HubSiteLoader { hub }.updates(site).first()
        assertEquals("router", update.nodeId)
        assertEquals(Fixtures.meshSite.getValue("router"), update.snapshot)
    }

    @Test
    fun `reboot is the agent's command, not a relayed ubus call`() = runTest {
        respond("""{"id":"c1","nodeId":"router","name":"reboot","status":"pending","createdAt":1}""", code = 202)
        respond("""{"id":"c1","nodeId":"router","name":"reboot","status":"succeeded","createdAt":1,"result":{}}""")
        HubNodeOperations.create(hub, "router", HubUbusCaller(hub, "router")).reboot()
        val issued = server.takeRequest()
        assertEquals("/app/v1/nodes/router/commands", issued.url.encodedPath)
        assertEquals(true, issued.body?.utf8()?.contains("\"name\":\"reboot\""))
    }

    @Test
    fun `counters renew the fast-mode lease only when it is due`() = runTest {
        var now = 0L
        val reader = HubCounterReader({ hub }, clock = { now }, leaseRenewMs = 45_000)
        val samples = """{"samples":[{"at":1,"devices":{"eth0":{"rx":1,"tx":2}}},{"at":2,"devices":{"eth0":{"rx":10,"tx":20}}}]}"""
        val lease = """{"id":"c1","nodeId":"router","name":"set_fast_mode","status":"succeeded","createdAt":1,"result":{}}"""
        val node = site.nodes.single()

        respond(lease, code = 202)
        respond(samples)
        assertEquals(mapOf("eth0" to DeviceCounters(10, 20)), reader.read(node))

        now = 30_000
        respond(samples)
        reader.read(node)

        now = 45_000
        respond(lease, code = 202)
        respond(samples)
        reader.read(node)

        val paths = List(server.requestCount) { server.takeRequest().url.encodedPath }
        assertEquals(
            listOf(
                "/app/v1/nodes/router/commands",
                "/app/v1/nodes/router/counters",
                "/app/v1/nodes/router/counters",
                "/app/v1/nodes/router/commands",
                "/app/v1/nodes/router/counters",
            ),
            paths,
        )
    }
}
