package wrthub.app.data

import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import wrthub.core.hub.HubClient

/** The bearer token for a hub site's requests: a stored device token, or a fresh OIDC access token. */
fun interface HubTokens {
    suspend fun bearer(link: HubLink): String
}

/** Device tokens are stored as they are; OIDC sites need [oidc], which refreshes tokens as they expire. */
class StoredHubTokens(private val cipher: SecretCipher, private val oidc: HubTokens? = null) : HubTokens {
    override suspend fun bearer(link: HubLink): String = when (link.auth) {
        HubAuth.DEVICE -> cipher.decrypt(link.credentialCipher)
        HubAuth.OIDC -> requireNotNull(oidc) { "OIDC sign-in is not available" }.bearer(link)
    }
}

/** One [HubClient] per server and credential; the credential is read again for every request. */
class HubConnections(private val http: OkHttpClient, private val tokens: HubTokens) {
    private val cache = ConcurrentHashMap<HubLink, HubClient>()

    fun client(link: HubLink): HubClient = cache.getOrPut(link) { HubClient(link.serverUrl, http) { tokens.bearer(link) } }
}
