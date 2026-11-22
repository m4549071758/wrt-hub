package wrthub.app.ui.hub

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import wrthub.app.data.HubAuth
import wrthub.app.data.HubLink
import wrthub.app.data.OidcSession
import wrthub.app.data.SecretCipher
import wrthub.app.data.SiteConfig
import wrthub.app.data.SiteRepository
import wrthub.app.domain.hubNodeConfig
import wrthub.core.hub.HubClient
import wrthub.core.hub.HubConfig
import wrthub.core.hub.HubHttpException
import wrthub.core.hub.HubOidc
import wrthub.core.hub.HubSite
import wrthub.core.hub.normalizeHubUrl
import wrthub.core.hub.parsePairingLink

enum class AddHubError { MISSING_SERVER, MISSING_CODE, INVALID_URL, INVALID_QR, UNREACHABLE, PAIRING_REJECTED, NO_OIDC, SIGN_IN_FAILED, NO_SITES }

sealed interface AddHubStep {
    data object Form : AddHubStep

    data object Working : AddHubStep

    /** The screen opens the provider's sign-in page and reports back with [AddHubViewModel.onSignedIn]. */
    data class SigningIn(val oidc: HubOidc) : AddHubStep

    data class ChooseSites(val sites: List<HubSite>) : AddHubStep

    data object Done : AddHubStep
}

data class AddHubUiState(
    val server: String = "",
    val code: String = "",
    val step: AddHubStep = AddHubStep.Form,
    val chosen: Set<String> = emptySet(),
    val error: AddHubError? = null,
    val errorDetail: String? = null,
)

/**
 * Adds sites of a WrtHub server: pairs this phone with a one-time code (typed, pasted as a `wrthub://` link or
 * scanned) or signs in with the server's OIDC provider, then stores the chosen sites with the credential encrypted.
 */
class AddHubViewModel(
    private val repository: SiteRepository,
    private val http: OkHttpClient,
    private val cipher: SecretCipher,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(AddHubUiState())
    val state: StateFlow<AddHubUiState> = mutableState.asStateFlow()

    /** Set once connected: where the chosen sites will be stored from. */
    private data class Connection(val serverUrl: String, val config: HubConfig, val auth: HubAuth, val credential: String)

    private var connection: Connection? = null

    /** A pasted pairing link fills both fields. */
    fun onServer(value: String) {
        val link = parsePairingLink(value.trim())
        mutableState.update {
            if (link != null) it.copy(server = link.server, code = link.code, error = null) else it.copy(server = value, error = null)
        }
    }

    fun onCode(value: String) = mutableState.update { it.copy(code = value, error = null) }

    fun onScanned(text: String) {
        val link = parsePairingLink(text.trim())
        if (link == null) {
            mutableState.update { it.copy(error = AddHubError.INVALID_QR, errorDetail = null) }
        } else {
            mutableState.update { it.copy(server = link.server, code = link.code, error = null) }
            pair()
        }
    }

    fun onScanFailed(detail: String?) = mutableState.update { it.copy(error = AddHubError.INVALID_QR, errorDetail = detail) }

    /** Pairs with the code. */
    fun pair() {
        val code = mutableState.value.code.trim()
        if (code.isEmpty()) {
            mutableState.update { it.copy(error = AddHubError.MISSING_CODE) }
            return
        }
        connect { serverUrl, config ->
            val token = try {
                HubClient(serverUrl, http).pair(code).token
            } catch (e: HubHttpException) {
                if (e.status in 400..499) return@connect fail(AddHubError.PAIRING_REJECTED) else throw e
            }
            listSites(Connection(serverUrl, config, HubAuth.DEVICE, token), token)
        }
    }

    /** Signs in with the server's identity provider instead of a code. */
    fun signIn() = connect { serverUrl, config ->
        val oidc = config.auth.oidc ?: return@connect fail(AddHubError.NO_OIDC)
        connection = Connection(serverUrl, config, HubAuth.OIDC, "")
        mutableState.update { it.copy(step = AddHubStep.SigningIn(oidc)) }
    }

    fun onSignedIn(session: OidcSession) {
        val pending = connection ?: return
        mutableState.update { it.copy(step = AddHubStep.Working) }
        viewModelScope.launch {
            try {
                listSites(pending.copy(credential = session.authState), session.accessToken)
            } catch (e: IOException) {
                fail(AddHubError.UNREACHABLE, e.message)
            }
        }
    }

    fun onSignInFailed(detail: String?) = fail(AddHubError.SIGN_IN_FAILED, detail)

    fun toggle(siteId: String) = mutableState.update { state ->
        state.copy(chosen = if (siteId in state.chosen) state.chosen - siteId else state.chosen + siteId)
    }

    /** Stores the chosen sites; a site already added from this server is updated in place, keeping its id. */
    fun save() {
        val current = connection ?: return
        val step = mutableState.value.step as? AddHubStep.ChooseSites ?: return
        val chosen = step.sites.filter { it.id in mutableState.value.chosen }
        if (chosen.isEmpty()) return
        mutableState.update { it.copy(step = AddHubStep.Working) }
        viewModelScope.launch {
            val existing = repository.sites.first()
            val stored = cipher.encrypt(current.credential)
            val saved = chosen.map { site ->
                val link = HubLink(current.serverUrl, site.id, current.auth, stored, starlink = current.config.features.starlink)
                val id = existing.firstOrNull { it.hub?.serverUrl == link.serverUrl && it.hub.siteId == site.id }?.id ?: newId()
                SiteConfig(id, site.name, site.nodes.map { hubNodeConfig(link, it.id, it.label) }, hub = link).also { repository.upsertSite(it) }
            }
            repository.select(saved.first().id)
            mutableState.update { it.copy(step = AddHubStep.Done) }
        }
    }

    /** Back from the site list or a sign-in error to the form. */
    fun backToForm() = mutableState.update { it.copy(step = AddHubStep.Form) }

    private fun connect(then: suspend (serverUrl: String, config: HubConfig) -> Unit) {
        val input = mutableState.value.server.trim()
        if (input.isEmpty()) {
            mutableState.update { it.copy(error = AddHubError.MISSING_SERVER) }
            return
        }
        val serverUrl = try {
            normalizeHubUrl(input)
        } catch (e: IllegalArgumentException) {
            mutableState.update { it.copy(error = AddHubError.INVALID_URL) }
            return
        }
        mutableState.update { it.copy(step = AddHubStep.Working, error = null, errorDetail = null) }
        viewModelScope.launch {
            try {
                then(serverUrl, HubClient(serverUrl, http).config())
            } catch (e: IOException) {
                fail(AddHubError.UNREACHABLE, e.message)
            }
        }
    }

    private suspend fun listSites(current: Connection, token: String) {
        val sites = HubClient(current.serverUrl, http) { token }.sites().sites
        if (sites.isEmpty()) return fail(AddHubError.NO_SITES)
        connection = current
        mutableState.update { it.copy(step = AddHubStep.ChooseSites(sites), chosen = if (sites.size == 1) setOf(sites.single().id) else emptySet()) }
    }

    private fun fail(error: AddHubError, detail: String? = null) =
        mutableState.update { it.copy(step = AddHubStep.Form, error = error, errorDetail = detail) }
}
