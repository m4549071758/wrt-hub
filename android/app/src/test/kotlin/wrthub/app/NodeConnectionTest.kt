package wrthub.app

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import okhttp3.OkHttpClient
import org.junit.Test
import wrthub.app.data.clientFor
import wrthub.app.data.normalizeNodeUrl
import wrthub.app.data.ubusEndpoint

class NodeConnectionTest {
    @Test
    fun `bare host gets http and paths are dropped`() {
        assertEquals("http://192.168.1.1", normalizeNodeUrl(" 192.168.1.1 "))
        assertEquals("https://10.0.0.1:8443", normalizeNodeUrl("https://10.0.0.1:8443/cgi-bin/luci?x=1"))
        assertEquals("http://router.lan", normalizeNodeUrl("router.lan/"))
    }

    @Test
    fun `invalid addresses are rejected`() {
        assertFailsWith<IllegalArgumentException> { normalizeNodeUrl("http://") }
        assertFailsWith<IllegalArgumentException> { normalizeNodeUrl("ftp://router") }
        assertFailsWith<IllegalArgumentException> { normalizeNodeUrl("exa mple") }
    }

    @Test
    fun `ubus endpoint is the ubus path of the node`() {
        assertEquals("http://192.168.1.1/ubus", ubusEndpoint("http://192.168.1.1").toString())
    }

    @Test
    fun `https needs a pinned certificate and http reuses the base client`() {
        val base = OkHttpClient()
        assertSame(base, clientFor("http://192.168.1.1", null, base))
        assertFailsWith<IllegalArgumentException> { clientFor("https://192.168.1.1", null, base) }
    }
}
