package wrthub.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import wrthub.core.ubus.DirectNode
import wrthub.core.ubus.UbusHttpClient
import wrthub.core.ubus.call

class DirectNodeTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun respond(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private fun login(id: String) =
        respond("""{"jsonrpc":"2.0","id":1,"result":[0,{"ubus_rpc_session":"$id","timeout":300}]}""")

    private fun ok() = respond("""{"jsonrpc":"2.0","id":1,"result":[0,{"uptime":1}]}""")

    @Test
    fun `reuses the session while fresh and logs in again after the idle timeout`() = runTest {
        var now = 0L
        val node = DirectNode(UbusHttpClient(server.url("/ubus"), OkHttpClient()), "root", "pw") { now }
        val first = "a".repeat(32)
        val second = "b".repeat(32)
        login(first); ok(); ok(); login(second); ok()

        node.call("system", "info")
        now += 200_000
        node.call("system", "info")
        now += 280_000
        node.call("system", "info")

        val sids = List(5) {
            Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
                .getValue("params").jsonArray[0].jsonPrimitive.content
        }
        assertEquals(
            listOf(UbusHttpClient.ANONYMOUS_SESSION, first, first, UbusHttpClient.ANONYMOUS_SESSION, second),
            sids,
        )
    }
}
