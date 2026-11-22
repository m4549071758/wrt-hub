package wrthub.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import wrthub.core.hub.HubOidc

/** A finished sign-in: the AuthState JSON to store and an access token to use right away. */
data class OidcSession(val authState: String, val accessToken: String)

/** Signs in to a hub's identity provider in the browser: authorization code with PKCE, through AppAuth. */
interface HubSignIn {
    suspend fun intent(oidc: HubOidc): Intent

    suspend fun finish(result: Intent): OidcSession
}

private fun failure(what: String, error: AuthorizationException?) =
    IOException("$what: ${error?.errorDescription ?: error?.error ?: "no answer"}", error)

class OidcAuth(context: Context) : HubSignIn {
    private val service = AuthorizationService(context.applicationContext)

    override suspend fun intent(oidc: HubOidc): Intent {
        val config = suspendCancellableCoroutine { cont ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(oidc.issuer)) { config, error ->
                if (config != null) cont.resume(config) else cont.resumeWithException(failure("discovery", error))
            }
        }
        // Refresh tokens need offline_access at most providers; Pocket ID issues them anyway and accepts the scope.
        val request = AuthorizationRequest.Builder(config, oidc.clientId, ResponseTypeValues.CODE, REDIRECT_URI)
            .setScopes("openid", "offline_access")
        // An audience other than the client is an API resource (RFC 8707); without it the token's aud is the client.
        if (oidc.audience != oidc.clientId) request.setAdditionalParameters(mapOf("resource" to oidc.audience))
        return service.getAuthorizationRequestIntent(request.build())
    }

    override suspend fun finish(result: Intent): OidcSession {
        val response = AuthorizationResponse.fromIntent(result) ?: throw failure("sign-in", AuthorizationException.fromIntent(result))
        val state = AuthState(response, null)
        val tokens = suspendCancellableCoroutine { cont ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { tokens, error ->
                if (tokens != null) cont.resume(tokens) else cont.resumeWithException(failure("token", error))
            }
        }
        state.update(tokens, null)
        val access = tokens.accessToken ?: throw IOException("token: the provider returned no access token")
        return OidcSession(state.jsonSerializeString(), access)
    }

    /** A valid access token for [state], refreshing it (and so changing [state]) when it has expired. */
    suspend fun fresh(state: AuthState): String = suspendCancellableCoroutine { cont ->
        state.performActionWithFreshTokens(service) { access, _, error ->
            if (access != null) cont.resume(access) else cont.resumeWithException(failure("refresh", error))
        }
    }

    private companion object {
        val REDIRECT_URI: Uri = Uri.parse("io.github.m4549071758.wrthub:/oauth2redirect")
    }
}

/**
 * Access tokens for OIDC hub sites. A refresh changes the AuthState, so the new state is stored again, in every site
 * of that server. States are kept by the stored ciphertext: a re-stored state is read back once under its new
 * ciphertext, and requests still holding the old one keep using the same updated object.
 */
class OidcHubTokens(private val cipher: SecretCipher, private val auth: OidcAuth, private val repository: SiteRepository) : HubTokens {
    private val states = ConcurrentHashMap<String, AuthState>()
    private val lock = Mutex()

    override suspend fun bearer(link: HubLink): String = lock.withLock {
        val state = states.getOrPut(link.credentialCipher) { AuthState.jsonDeserialize(cipher.decrypt(link.credentialCipher)) }
        val before = state.jsonSerializeString()
        val token = auth.fresh(state)
        val after = state.jsonSerializeString()
        if (after != before) {
            val stored = cipher.encrypt(after)
            states[stored] = state
            repository.sites.first()
                .filter { it.hub?.serverUrl == link.serverUrl && it.hub.auth == HubAuth.OIDC }
                .forEach { site -> repository.upsertSite(site.copy(hub = site.hub?.copy(credentialCipher = stored))) }
        }
        token
    }
}
