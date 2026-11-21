package wrthub.app

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import wrthub.app.data.NodeConfig
import wrthub.app.data.NodeVerifying
import wrthub.app.data.SecretCipher
import wrthub.app.data.SiteConfig
import wrthub.app.ui.sites.AddNodeError
import wrthub.app.ui.sites.AddNodeStep
import wrthub.app.ui.sites.AddNodeViewModel
import wrthub.core.ubus.UbusException

private class FakeVerifier(private val failure: Exception? = null) : NodeVerifying {
    var probes = 0
    val verified = mutableListOf<Pair<String, String?>>()

    override suspend fun probeCertificate(nodeUrl: String): String {
        probes++
        return "AA:BB"
    }

    override suspend fun verify(nodeUrl: String, pinnedSha256: String?, username: String, password: String): String {
        failure?.let { throw it }
        verified += nodeUrl to pinnedSha256
        return "OpenWrt"
    }
}

private object PrefixCipher : SecretCipher {
    override fun encrypt(plaintext: String) = "enc:$plaintext"

    override fun decrypt(ciphertext: String) = ciphertext.removePrefix("enc:")
}

@OptIn(ExperimentalCoroutinesApi::class)
class AddNodeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private fun viewModel(siteId: String?, repository: FakeSiteRepository, verifier: NodeVerifying): AddNodeViewModel {
        var next = 0
        return AddNodeViewModel(siteId, repository, verifier, PrefixCipher) { "id${++next}" }
    }

    private fun AddNodeViewModel.fill(url: String, siteName: String = "Home") {
        onSiteName(siteName)
        onLabel("Router")
        onUrl(url)
        onPassword("pw")
    }

    @Test
    fun `http node creates a site with an encrypted password and selects it`() = runTest(main.dispatcher) {
        val repository = FakeSiteRepository()
        val vm = viewModel(null, repository, FakeVerifier())
        vm.fill("192.168.1.1")
        vm.submit()
        advanceUntilIdle()
        assertEquals(AddNodeStep.Done, vm.state.value.step)
        val expected = SiteConfig("id2", "Home", listOf(NodeConfig("id1", "Router", "http://192.168.1.1", "root", "enc:pw", null)))
        assertEquals(listOf(expected), repository.current)
        assertEquals("id2", repository.currentSelection)
    }

    @Test
    fun `https node asks to trust the certificate and pins it`() = runTest(main.dispatcher) {
        val repository = FakeSiteRepository()
        val verifier = FakeVerifier()
        val vm = viewModel(null, repository, verifier)
        vm.fill("https://10.0.0.1")
        vm.submit()
        advanceUntilIdle()
        assertEquals(AddNodeStep.ConfirmCertificate("AA:BB"), vm.state.value.step)
        assertTrue(repository.current.isEmpty())
        vm.trustCertificate()
        advanceUntilIdle()
        assertEquals(AddNodeStep.Done, vm.state.value.step)
        assertEquals(listOf<Pair<String, String?>>("https://10.0.0.1" to "AA:BB"), verifier.verified)
        assertEquals("AA:BB", repository.current.single().nodes.single().certSha256)
    }

    @Test
    fun `rejecting the certificate saves nothing`() = runTest(main.dispatcher) {
        val repository = FakeSiteRepository()
        val vm = viewModel(null, repository, FakeVerifier())
        vm.fill("https://10.0.0.1")
        vm.submit()
        advanceUntilIdle()
        vm.rejectCertificate()
        assertEquals(AddNodeError.CERTIFICATE_REJECTED, vm.state.value.error)
        assertEquals(AddNodeStep.Form, vm.state.value.step)
        assertTrue(repository.current.isEmpty())
    }

    @Test
    fun `wrong password is reported and nothing is saved`() = runTest(main.dispatcher) {
        val repository = FakeSiteRepository()
        val vm = viewModel(null, repository, FakeVerifier(UbusException(UbusException.PERMISSION_DENIED, "denied")))
        vm.fill("192.168.1.1")
        vm.submit()
        advanceUntilIdle()
        assertEquals(AddNodeError.LOGIN_FAILED, vm.state.value.error)
        assertTrue(repository.current.isEmpty())
    }

    @Test
    fun `node is appended to an existing site without asking for a site name`() = runTest(main.dispatcher) {
        val existing = SiteConfig("s1", "Cabin", listOf(node("n0")))
        val repository = FakeSiteRepository(listOf(existing))
        val vm = viewModel("s1", repository, FakeVerifier())
        assertEquals(false, vm.state.value.needsSiteName)
        vm.fill("192.168.1.2", siteName = "")
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("n0", "id1"), repository.current.single().nodes.map { it.id })
    }

    @Test
    fun `invalid input never reaches the network`() = runTest(main.dispatcher) {
        val verifier = FakeVerifier()
        val vm = viewModel(null, FakeSiteRepository(), verifier)
        vm.submit()
        assertEquals(AddNodeError.MISSING_FIELDS, vm.state.value.error)
        vm.fill("http://")
        vm.submit()
        advanceUntilIdle()
        assertEquals(AddNodeError.INVALID_URL, vm.state.value.error)
        assertEquals(0, verifier.probes)
        assertTrue(verifier.verified.isEmpty())
    }
}
