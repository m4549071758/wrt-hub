package wrthub.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import wrthub.core.hub.HubClient
import wrthub.core.hub.HubCommandException
import wrthub.core.hub.HubHttpException
import wrthub.core.hub.HubUbusCaller
import wrthub.core.hub.PairingLink
import wrthub.core.hub.normalizeHubUrl
import wrthub.core.hub.parsePairingLink
import wrthub.core.ubus.UbusException

class HubClientTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun client(token: String? = "t0ken") =
        HubClient(server.url("/").toString(), OkHttpClient(), token?.let { t -> { t } })

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun `config and pairing need no token, later calls carry it`() = runTest {
        respond("""{"auth":{"pairing":true,"oidc":{"issuer":"https://id.example","clientId":"app","audience":"hub"}},"features":{"starlink":true}}""")
        val config = HubClient(server.url("/").toString(), OkHttpClient()).config()
        assertEquals("https://id.example", config.auth.oidc?.issuer)
        assertTrue(config.features.starlink)
        assertNull(server.takeRequest().headers["Authorization"])

        respond("""{"serverTime":1,"sites":[{"id":"s1","name":"Cabin","nodes":[{"id":"n1","label":"Router","state":"fresh","receivedAt":1}]}]}""")
        val sites = client().sites()
        assertEquals("Router", sites.sites.single().nodes.single().label)
        val request = server.takeRequest()
        assertEquals("/app/v1/sites", request.url.encodedPath)
        assertEquals("Bearer t0ken", request.headers["Authorization"])
    }

    @Test
    fun `a command is issued and long-polled until it finishes`() = runTest {
        respond("""{"id":"c1","nodeId":"n1","name":"ping","status":"pending"}""", 202)
        respond("""{"id":"c1","nodeId":"n1","name":"ping","status":"sent"}""")
        respond("""{"id":"c1","nodeId":"n1","name":"ping","status":"succeeded","result":{"exitCode":0}}""")
        val result = client().run("n1", "ping", buildJsonObject { put("host", "1.1.1.1") })
        assertEquals("0", result!!.jsonObject.getValue("exitCode").jsonPrimitive.content)
        val issue = server.takeRequest()
        assertEquals("/app/v1/nodes/n1/commands", issue.url.encodedPath)
        assertEquals("""{"name":"ping","args":{"host":"1.1.1.1"}}""", issue.body!!.utf8())
        assertEquals("wait=25", server.takeRequest().url.encodedQuery)
    }

    @Test
    fun `failed and expired commands and HTTP errors throw`() = runTest {
        respond("""{"id":"c1","nodeId":"n1","name":"reboot","status":"expired"}""", 202)
        assertFailsWith<HubCommandException> { client().run("n1", "reboot") }
        respond("""{"error":"unauthorized"}""", 401)
        val denied = assertFailsWith<HubHttpException> { client().sites() }
        assertEquals(401, denied.status)
        assertTrue("unauthorized" in denied.message!!)
    }

    @Test
    fun `relayed ubus calls return the data or throw the ubus status`() = runTest {
        respond("""{"id":"c1","nodeId":"n1","name":"ubus","status":"succeeded","result":{"code":0,"data":{"values":{}}}}""", 202)
        respond("""{"id":"c2","nodeId":"n1","name":"ubus","status":"succeeded","result":{"code":6,"data":null,"error":"config rpcd is not allowed"}}""", 202)
        respond("""{"id":"c3","nodeId":"n1","name":"ubus","status":"succeeded","result":{"code":0,"data":{"code":0,"stdout":"line1\nline2\n","stderr":""}}}""", 202)
        val caller = HubUbusCaller(client(), "n1")
        assertEquals(JsonObject(mapOf("values" to JsonObject(emptyMap()))), caller.call("uci", "get", buildJsonObject { put("config", "dhcp") }))
        val sent = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("""{"object":"uci","method":"get","args":{"config":"dhcp"}}""", sent.getValue("args").toString())
        val refused = assertFailsWith<UbusException> { caller.call("uci", "get", buildJsonObject { put("config", "rpcd") }) }
        assertEquals(UbusException.PERMISSION_DENIED, refused.code)
        assertTrue("not allowed" in refused.message!!)
        assertEquals(listOf("line1", "line2"), caller.stream("/usr/libexec/package-manager-call", listOf("list-installed")) { it.bufferedReader().readLines() })
    }

    @Test
    fun `the event stream yields snapshots`() = runTest {
        val events = """
            event: snapshot
            data: {"nodeId":"n1","receivedAt":10,"serverTime":11,"snapshot":{"schemaVersion":1}}

            : heartbeat

            event: snapshot
            data: {"nodeId":"n2","receivedAt":12,"serverTime":13,"snapshot":{"schemaVersion":1}}


        """.trimIndent()
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/event-stream").body(events).build())
        val received = client().stream("s1").take(2).toList()
        assertEquals(listOf("n1", "n2"), received.map { it.nodeId })
        assertEquals("Bearer t0ken", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `server addresses and pairing links`() {
        assertEquals("https://hub.example/", normalizeHubUrl("hub.example"))
        assertEquals("http://10.0.0.5:8080/", normalizeHubUrl(" http://10.0.0.5:8080 "))
        assertEquals("https://hub.example/wrthub/", normalizeHubUrl("https://hub.example/wrthub?x=1"))
        assertEquals(
            PairingLink("https://hub.example", "ABCD-EFGH-JKLM"),
            parsePairingLink("wrthub://pair?server=https%3A%2F%2Fhub.example&code=ABCD-EFGH-JKLM"),
        )
        assertNull(parsePairingLink("https://hub.example/?code=x"))
        assertNull(parsePairingLink("wrthub://pair?server=https%3A%2F%2Fhub.example"))
    }
}
