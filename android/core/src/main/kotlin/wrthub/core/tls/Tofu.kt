package wrthub.core.tls

import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

fun sha256Fingerprint(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

/**
 * Trusts exactly the leaf certificate whose SHA-256 fingerprint is [pinnedSha256]. With a null pin it accepts any
 * certificate and only reports it through [onObserved]; use that only for [probeFingerprint], never with credentials.
 */
class TofuTrustManager(
    private val pinnedSha256: String?,
    private val onObserved: (String) -> Unit = {},
) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("server sent no certificate")
        val observed = sha256Fingerprint(leaf)
        onObserved(observed)
        if (pinnedSha256 != null && !pinnedSha256.equals(observed, ignoreCase = true)) {
            throw CertificateException("server certificate changed: pinned $pinnedSha256, got $observed")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String): Unit =
        throw CertificateException("client certificates are not supported")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** uhttpd's default certificate is self-signed without an IP SAN, so the pin replaces hostname verification. */
fun OkHttpClient.Builder.trustOnFirstUse(pinnedSha256: String?, onObserved: (String) -> Unit = {}): OkHttpClient.Builder {
    val trustManager = TofuTrustManager(pinnedSha256, onObserved)
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    return sslSocketFactory(context.socketFactory, trustManager).hostnameVerifier { _, _ -> true }
}

/** Connects without credentials and returns the presented certificate's fingerprint for the user to confirm. */
suspend fun probeFingerprint(url: HttpUrl, base: OkHttpClient = OkHttpClient()): String {
    require(url.isHttps) { "fingerprint probe needs an https URL, got $url" }
    var observed: String? = null
    val client = base.newBuilder().trustOnFirstUse(null) { observed = it }.build()
    withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).head().build()).execute().close()
    }
    return observed ?: throw IOException("TLS handshake with $url presented no certificate")
}
