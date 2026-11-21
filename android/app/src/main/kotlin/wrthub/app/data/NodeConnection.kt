package wrthub.app.data

import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import wrthub.core.tls.trustOnFirstUse
import wrthub.core.ubus.DirectNode
import wrthub.core.ubus.UbusHttpClient

/** Accepts `host`, `host:port` or a full http(s) URL and returns `scheme://host[:port]`. */
fun normalizeNodeUrl(input: String): String {
    val trimmed = input.trim()
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val url = withScheme.toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid node address: $input")
    return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
}

fun ubusEndpoint(nodeUrl: String): HttpUrl {
    val base = nodeUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("invalid node address: $nodeUrl")
    return base.newBuilder().encodedPath("/ubus").query(null).fragment(null).build()
}

/** HTTP nodes share [base]; HTTPS nodes get a client that trusts only their pinned certificate. */
fun clientFor(nodeUrl: String, pinnedSha256: String?, base: OkHttpClient): OkHttpClient {
    if (!ubusEndpoint(nodeUrl).isHttps) return base
    requireNotNull(pinnedSha256) { "HTTPS node $nodeUrl has no pinned certificate" }
    return base.newBuilder().trustOnFirstUse(pinnedSha256).build()
}

/** Keeps one logged-in [DirectNode] per node and rebuilds it when the node's settings change. */
class NodeConnections(private val http: OkHttpClient, private val cipher: SecretCipher) {
    private val cache = ConcurrentHashMap<String, Pair<NodeConfig, DirectNode>>()

    fun node(config: NodeConfig): DirectNode =
        cache.compute(config.id) { _, cached -> if (cached?.first == config) cached else config to create(config) }!!.second

    private fun create(config: NodeConfig): DirectNode = DirectNode(
        UbusHttpClient(ubusEndpoint(config.url), clientFor(config.url, config.certSha256, http)),
        config.username,
        cipher.decrypt(config.passwordCipher),
    )
}
