package wrthub.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import wrthub.core.admin.PackageInfo
import wrthub.core.admin.Storage
import wrthub.core.admin.isEssentialPackage
import wrthub.core.admin.parsePackages
import wrthub.core.admin.parseStorage
import wrthub.core.admin.requireValidPackageName
import wrthub.core.direct.DirectAdmin
import wrthub.core.ubus.UbusException
import wrthub.core.ubus.UbusHttpClient
import wrthub.core.ubus.UbusSession
import wrthub.core.ubus.cgiCommandLine
import wrthub.core.json.str

class PackagesTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private val listing = """[
        {"package":"tcpdump-mini-4.99","name":"tcpdump-mini","version":"4.99.5-r1","description":"Network monitoring","installed-size":385024,
         "depends":["libc","libpcap1"],"provides":["tcpdump-any"],"repositories":["lib/apk/db/installed"]},
        {"name":"apk-mbedtls","version":"3.0.5-r3","installed-size":232023,"maintainer":"someone"},
        {"name":"tcpdump-mini","version":"4.99.4-r1"}
    ]"""

    @Test
    fun `package lists are read element by element, keeping one entry per name`() {
        val packages = parsePackages(listing.byteInputStream())
        assertEquals(
            listOf(
                PackageInfo("apk-mbedtls", "3.0.5-r3", null, 232023, emptyList()),
                PackageInfo("tcpdump-mini", "4.99.5-r1", "Network monitoring", 385024, listOf("libc", "libpcap1")),
            ),
            packages,
        )
    }

    @Test
    fun `cgi-exec gets the command line escaped like luci and streams the body`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(listing).build())
        val client = UbusHttpClient(server.url("/ubus"), OkHttpClient())
        val session = UbusSession("0123456789abcdef0123456789abcdef", 300)
        val packages = client.cgiExec(session, cgiCommandLine("/usr/libexec/package-manager-call", listOf("list-installed")), ::parsePackages)
        assertEquals(2, packages.size)
        val request = server.takeRequest()
        assertEquals("/cgi-bin/cgi-exec", request.url.encodedPath)
        val form = request.body!!.utf8()
        assertEquals("sessionid=${session.id}&command=%2Fusr%2Flibexec%2Fpackage-manager-call%20list-installed&stderr=0", form)
        assertEquals("a\\ b c\\\\d", cgiCommandLine("a b", listOf("c\\d")))
    }

    @Test
    fun `a refused cgi-exec is an access denial`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).build())
        val client = UbusHttpClient(server.url("/ubus"), OkHttpClient())
        val error = assertFailsWith<UbusException> { client.cgiExec(UbusSession("x", 300), "/bin/true") { it.readBytes() } }
        assertEquals(UbusException.PERMISSION_DENIED, error.code)
    }

    @Test
    fun `package names cannot smuggle options and essential packages cannot be removed`() = runTest {
        assertFailsWith<IllegalArgumentException> { requireValidPackageName("--force-broken-world") }
        assertFailsWith<IllegalArgumentException> { requireValidPackageName("a b") }
        requireValidPackageName("luci-app-sqm")
        assertTrue(isEssentialPackage("rpcd-mod-luci"))
        assertTrue(isEssentialPackage("uhttpd"))
        assertFalse(isEssentialPackage("tcpdump-mini"))
        val fake = FakeUbus { _, _, _ -> null }
        assertFailsWith<IllegalArgumentException> { DirectAdmin(fake).removePackage("luci-base") }
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `install reports apk's result`() = runTest {
        val fake = FakeUbus { obj, method, args ->
            if ("$obj.$method" == "file.exec") {
                buildJsonObject {
                    put("code", 0)
                    put("stdout", """{"code":1,"pkmcmd":"apk add nosuch","stderr":"ERROR: unable to select packages"}""")
                }
            } else {
                null
            }
        }
        val result = DirectAdmin(fake).installPackage("nosuch")
        assertEquals(1, result.code)
        assertEquals("ERROR: unable to select packages", result.stderr)
        assertEquals(listOf("install", "nosuch"), fake.calls.single().args["params"].toString().let { Json.parseToJsonElement(it) }.let { el ->
            (el as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') }
        })
    }

    @Test
    fun `free space comes from overlay, else from the root`() {
        val flash = Json.parseToJsonElement(
            """{"result":[{"mount":"/","size":1,"avail":0},{"mount":"/overlay","size":8000000,"avail":4500000}]}""",
        ).jsonObject
        assertEquals(Storage("/overlay", 8000000, 4500000), parseStorage(flash))
        val x86 = Json.parseToJsonElement("""{"result":[{"mount":"/","size":250,"avail":200},{"mount":"/tmp","size":4,"avail":4}]}""").jsonObject
        assertEquals("/", parseStorage(x86)!!.mount)
    }
}
