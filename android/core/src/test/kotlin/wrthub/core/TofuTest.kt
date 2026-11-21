package wrthub.core

import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import wrthub.core.tls.probeFingerprint
import wrthub.core.tls.sha256Fingerprint
import wrthub.core.tls.trustOnFirstUse

class TofuTest {
    private val certificate = HeldCertificate.Builder().commonName("OpenWrt").build()
    private val server = MockWebServer()

    @BeforeTest
    fun start() {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory())
        server.start()
    }

    @AfterTest
    fun stop() = server.close()

    private fun ok() = server.enqueue(MockResponse.Builder().body("ok").build())

    private fun get(client: OkHttpClient) =
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.code }

    @Test
    fun `probe reports the self-signed certificate fingerprint`() = runTest {
        ok()
        assertEquals(sha256Fingerprint(certificate.certificate), probeFingerprint(server.url("/")))
    }

    @Test
    fun `pinned fingerprint is accepted`() {
        ok()
        val pinned = sha256Fingerprint(certificate.certificate)
        assertEquals(200, get(OkHttpClient.Builder().trustOnFirstUse(pinned).build()))
    }

    @Test
    fun `different fingerprint is rejected`() {
        val e = assertFailsWith<IOException> { get(OkHttpClient.Builder().trustOnFirstUse("00:11:22").build()) }
        assertTrue(e.causes().any { it is SSLHandshakeException }, e.toString())
    }

    /** OkHttp retries the next route after a failed handshake and reports earlier failures as suppressed. */
    private fun Throwable.causes(): Sequence<Throwable> = sequence {
        yield(this@causes)
        cause?.let { yieldAll(it.causes()) }
        suppressed.forEach { yieldAll(it.causes()) }
    }
}
