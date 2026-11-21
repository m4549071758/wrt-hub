package wrthub.app.ui.sites

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
import wrthub.app.data.NodeConfig
import wrthub.app.data.NodeVerifying
import wrthub.app.data.SecretCipher
import wrthub.app.data.SiteConfig
import wrthub.app.data.SiteRepository
import wrthub.app.data.normalizeNodeUrl
import wrthub.core.ubus.UbusException

enum class AddNodeError { MISSING_FIELDS, INVALID_URL, LOGIN_FAILED, UNREACHABLE, CERTIFICATE_REJECTED, SITE_NOT_FOUND }

sealed interface AddNodeStep {
    data object Form : AddNodeStep

    data object Working : AddNodeStep

    data class ConfirmCertificate(val fingerprint: String) : AddNodeStep

    data object Done : AddNodeStep
}

data class AddNodeUiState(
    val needsSiteName: Boolean,
    val siteName: String = "",
    val label: String = "",
    val url: String = "",
    val username: String = "root",
    val password: String = "",
    val step: AddNodeStep = AddNodeStep.Form,
    val error: AddNodeError? = null,
    val errorDetail: String? = null,
)

/** Adds a node to the site [siteId], or creates a new site when [siteId] is null. Saves only after a successful login. */
class AddNodeViewModel(
    private val siteId: String?,
    private val repository: SiteRepository,
    private val verifier: NodeVerifying,
    private val cipher: SecretCipher,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val mutableState = MutableStateFlow(AddNodeUiState(needsSiteName = siteId == null))
    val state: StateFlow<AddNodeUiState> = mutableState.asStateFlow()

    fun onSiteName(value: String) = mutableState.update { it.copy(siteName = value) }

    fun onLabel(value: String) = mutableState.update { it.copy(label = value) }

    fun onUrl(value: String) = mutableState.update { it.copy(url = value) }

    fun onUsername(value: String) = mutableState.update { it.copy(username = value) }

    fun onPassword(value: String) = mutableState.update { it.copy(password = value) }

    fun submit() {
        val s = mutableState.value
        if (s.step != AddNodeStep.Form) return
        val missing = (s.needsSiteName && s.siteName.isBlank()) || s.label.isBlank() || s.url.isBlank() ||
            s.username.isBlank() || s.password.isEmpty()
        if (missing) return fail(AddNodeError.MISSING_FIELDS)
        val url = try {
            normalizeNodeUrl(s.url)
        } catch (e: IllegalArgumentException) {
            return fail(AddNodeError.INVALID_URL)
        }
        mutableState.update { it.copy(url = url, step = AddNodeStep.Working, error = null, errorDetail = null) }
        viewModelScope.launch {
            if (url.startsWith("https://")) {
                try {
                    val fingerprint = verifier.probeCertificate(url)
                    mutableState.update { it.copy(step = AddNodeStep.ConfirmCertificate(fingerprint)) }
                } catch (e: IOException) {
                    fail(AddNodeError.UNREACHABLE, e.message)
                }
            } else {
                verifyAndSave(url, null)
            }
        }
    }

    fun trustCertificate() {
        val step = mutableState.value.step as? AddNodeStep.ConfirmCertificate ?: return
        mutableState.update { it.copy(step = AddNodeStep.Working) }
        viewModelScope.launch { verifyAndSave(mutableState.value.url, step.fingerprint) }
    }

    fun rejectCertificate() = fail(AddNodeError.CERTIFICATE_REJECTED)

    private suspend fun verifyAndSave(url: String, pin: String?) {
        val s = mutableState.value
        try {
            verifier.verify(url, pin, s.username.trim(), s.password)
        } catch (e: UbusException) {
            val denied = e.code == UbusException.PERMISSION_DENIED || e.code == UbusException.RPC_ACCESS_DENIED
            return fail(if (denied) AddNodeError.LOGIN_FAILED else AddNodeError.UNREACHABLE, e.message)
        } catch (e: IOException) {
            return fail(AddNodeError.UNREACHABLE, e.message)
        }
        val node = NodeConfig(newId(), s.label.trim(), url, s.username.trim(), cipher.encrypt(s.password), pin)
        val site = if (siteId == null) {
            SiteConfig(newId(), s.siteName.trim(), listOf(node))
        } else {
            val existing = repository.sites.first().firstOrNull { it.id == siteId } ?: return fail(AddNodeError.SITE_NOT_FOUND)
            existing.copy(nodes = existing.nodes + node)
        }
        repository.upsertSite(site)
        repository.select(site.id)
        mutableState.update { it.copy(step = AddNodeStep.Done, password = "") }
    }

    private fun fail(error: AddNodeError, detail: String? = null) =
        mutableState.update { it.copy(step = AddNodeStep.Form, error = error, errorDetail = detail) }
}
