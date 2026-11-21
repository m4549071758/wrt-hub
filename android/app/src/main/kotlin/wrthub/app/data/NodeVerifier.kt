package wrthub.app.data

import okhttp3.OkHttpClient
import wrthub.core.json.str
import wrthub.core.tls.probeFingerprint
import wrthub.core.ubus.DirectNode
import wrthub.core.ubus.UbusHttpClient
import wrthub.core.ubus.call

interface NodeVerifying {
    /** Fingerprint of the certificate an HTTPS node presents; no credentials are sent. */
    suspend fun probeCertificate(nodeUrl: String): String

    /** Logs in and reads the board; returns the router's hostname. */
    suspend fun verify(nodeUrl: String, pinnedSha256: String?, username: String, password: String): String
}

class DirectNodeVerifier(private val http: OkHttpClient) : NodeVerifying {
    override suspend fun probeCertificate(nodeUrl: String): String =
        probeFingerprint(ubusEndpoint(nodeUrl).newBuilder().encodedPath("/").build(), http)

    override suspend fun verify(nodeUrl: String, pinnedSha256: String?, username: String, password: String): String {
        val node = DirectNode(UbusHttpClient(ubusEndpoint(nodeUrl), clientFor(nodeUrl, pinnedSha256, http)), username, password)
        return node.call("system", "board")?.str("hostname").orEmpty()
    }
}
