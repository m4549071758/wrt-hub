package wrthub.app

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import wrthub.app.data.HubAuth
import wrthub.app.data.HubLink
import wrthub.app.data.OidcSession
import wrthub.app.data.SecretCipher
import wrthub.app.data.SiteConfig
import wrthub.app.ui.hub.AddHubError
import wrthub.app.ui.hub.AddHubStep
import wrthub.app.ui.hub.AddHubViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class AddHubViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private object Cipher : SecretCipher {
        override fun encrypt(plaintext: String) = "enc:$plaintext"

        override fun decrypt(ciphertext: String) = ciphertext.removePrefix("enc:")
    }

    private fun respond(body: String, code: Int = 200) = server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private val serverUrl get() = server.url("/").toString()

    private val config = """{"auth":{"pairing":true,"oidc":{"issuer":"https://id.example","clientId":"app","audience":"app"}},"features":{"starlink":true}}"""
    private val sites = """{"serverTime":1,"sites":[
        {"id":"cabin","name":"Cabin","nodes":[{"id":"r1","label":"Router","state":"fresh"}]},
        {"id":"office","name":"Office","nodes":[{"id":"o1","label":"Gateway","state":"never"}]}]}"""

    private suspend fun AddHubViewModel.settled() = state.first { it.step != AddHubStep.Working }

    @Test
    fun `a pasted link pairs and stores the chosen sites with the token`() = runTest {
        val repository = FakeSiteRepository()
        var ids = 0
        val vm = AddHubViewModel(repository, OkHttpClient(), Cipher) { "site-${++ids}" }
        vm.onServer("wrthub://pair?server=${java.net.URLEncoder.encode(serverUrl, Charsets.UTF_8)}&code=ABCD-EFGH-JKLM")
        assertEquals("ABCD-EFGH-JKLM", vm.state.value.code)

        respond(config)
        respond("""{"deviceId":"d1","token":"t0ken"}""")
        respond(sites)
        vm.pair()
        val choosing = assertIs<AddHubStep.ChooseSites>(vm.settled().step)
        assertEquals(listOf("cabin", "office"), choosing.sites.map { it.id })
        assertEquals(emptySet(), vm.state.value.chosen)

        server.takeRequest()
        assertEquals("/app/v1/pair", server.takeRequest().url.encodedPath)
        assertEquals("Bearer t0ken", server.takeRequest().headers["Authorization"])

        vm.toggle("office")
        vm.save()
        vm.state.first { it.step == AddHubStep.Done }
        val stored = repository.current.single()
        val link = HubLink(serverUrl, "office", HubAuth.DEVICE, "enc:t0ken", starlink = true)
        assertEquals(SiteConfig("site-1", "Office", listOf(wrthub.app.domain.hubNodeConfig(link, "o1", "Gateway")), hub = link), stored)
        assertEquals("site-1", repository.currentSelection)
    }

    @Test
    fun `adding a site again updates it in place`() = runTest {
        val old = HubLink(serverUrl, "cabin", HubAuth.DEVICE, "enc:old")
        val repository = FakeSiteRepository(listOf(SiteConfig("kept", "Cabin", hub = old)))
        val vm = AddHubViewModel(repository, OkHttpClient(), Cipher) { "new" }
        vm.onServer(serverUrl)
        vm.onCode("ABCD-EFGH-JKLM")
        respond(config)
        respond("""{"deviceId":"d2","token":"fresh"}""")
        respond("""{"serverTime":1,"sites":[{"id":"cabin","name":"Cabin","nodes":[]}]}""")
        vm.pair()
        vm.settled()
        assertEquals(setOf("cabin"), vm.state.value.chosen)
        vm.save()
        vm.state.first { it.step == AddHubStep.Done }
        assertEquals("kept", repository.current.single().id)
        assertEquals("enc:fresh", repository.current.single().hub?.credentialCipher)
    }

    @Test
    fun `a rejected code says so and stores nothing`() = runTest {
        val repository = FakeSiteRepository()
        val vm = AddHubViewModel(repository, OkHttpClient(), Cipher)
        vm.onServer(serverUrl)
        vm.onCode("WRONG")
        respond(config)
        respond("""{"error":"invalid pairing code"}""", code = 401)
        vm.pair()
        val state = vm.settled()
        assertEquals(AddHubStep.Form, state.step)
        assertEquals(AddHubError.PAIRING_REJECTED, state.error)
        assertEquals(emptyList(), repository.current)
    }

    @Test
    fun `sign-in asks for the provider, then lists the sites with its token`() = runTest {
        val repository = FakeSiteRepository()
        val vm = AddHubViewModel(repository, OkHttpClient(), Cipher) { "s" }
        vm.onServer(serverUrl)
        respond(config)
        vm.signIn()
        val signing = assertIs<AddHubStep.SigningIn>(vm.state.first { it.step is AddHubStep.SigningIn }.step)
        assertEquals("app", signing.oidc.clientId)

        respond(sites)
        vm.onSignedIn(OidcSession("""{"state":1}""", "access"))
        vm.settled()
        server.takeRequest()
        assertEquals("Bearer access", server.takeRequest().headers["Authorization"])
        vm.toggle("cabin")
        vm.save()
        vm.state.first { it.step == AddHubStep.Done }
        val hub = repository.current.single().hub
        assertEquals(HubAuth.OIDC, hub?.auth)
        assertEquals("""enc:{"state":1}""", hub?.credentialCipher)
    }

    @Test
    fun `missing fields and unreadable QR codes are reported without a request`() = runTest {
        val vm = AddHubViewModel(FakeSiteRepository(), OkHttpClient(), Cipher)
        vm.pair()
        assertEquals(AddHubError.MISSING_CODE, vm.state.value.error)
        vm.onCode("ABCD")
        vm.pair()
        assertEquals(AddHubError.MISSING_SERVER, vm.state.value.error)
        vm.onScanned("https://example.com/not-a-pairing-link")
        assertEquals(AddHubError.INVALID_QR, vm.state.value.error)
        assertNull(vm.state.value.errorDetail)
        assertEquals(0, server.requestCount)
    }
}
