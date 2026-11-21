package wrthub.core

import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import wrthub.core.json.long
import wrthub.core.ubus.UbusException
import wrthub.core.ubus.UbusHttpClient
import wrthub.core.ubus.UbusSession

class UbusHttpClientTest {
    private val server = MockWebServer()
    private val session = UbusSession("0123456789abcdef0123456789abcdef", 300)

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun client() = UbusHttpClient(server.url("/ubus"), OkHttpClient())

    private fun respond(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun sentParams() =
        Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject.getValue("params").jsonArray

    @Test
    fun `login returns the session and uses the anonymous session id`() = runTest {
        respond("""{"jsonrpc":"2.0","id":1,"result":[0,{"ubus_rpc_session":"${session.id}","timeout":300}]}""")
        assertEquals(session, client().login("root", "pw"))
        val params = sentParams()
        assertEquals(UbusHttpClient.ANONYMOUS_SESSION, params[0].jsonPrimitive.content)
        assertEquals("session", params[1].jsonPrimitive.content)
        assertEquals("login", params[2].jsonPrimitive.content)
        assertEquals("root", params[3].jsonObject.getValue("username").jsonPrimitive.content)
    }

    @Test
    fun `call returns the data object`() = runTest {
        respond("""{"jsonrpc":"2.0","id":1,"result":[0,{"uptime":42}]}""")
        assertEquals(42L, client().call(session, "system", "info")?.long("uptime"))
        assertEquals(session.id, sentParams()[0].jsonPrimitive.content)
    }

    @Test
    fun `call without data returns null`() = runTest {
        respond("""{"jsonrpc":"2.0","id":1,"result":[0]}""")
        assertNull(client().call(session, "uci", "confirm"))
    }

    @Test
    fun `non-zero ubus status throws with that code`() = runTest {
        respond("""{"jsonrpc":"2.0","id":1,"result":[6]}""")
        val e = assertFailsWith<UbusException> { client().call(session, "uci", "get", JsonObject(emptyMap())) }
        assertEquals(UbusException.PERMISSION_DENIED, e.code)
    }

    @Test
    fun `json-rpc error throws with the rpc code`() = runTest {
        respond("""{"jsonrpc":"2.0","id":1,"error":{"code":-32002,"message":"Access denied"}}""")
        val e = assertFailsWith<UbusException> { client().call(session, "system", "info") }
        assertEquals(UbusException.RPC_ACCESS_DENIED, e.code)
    }

    @Test
    fun `http error throws IOException`() = runTest {
        respond("oops", code = 500)
        assertFailsWith<IOException> { client().call(session, "system", "info") }
    }
}
