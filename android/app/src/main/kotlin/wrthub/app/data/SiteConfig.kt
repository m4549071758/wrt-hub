package wrthub.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class NodeConfig(
    val id: String,
    val label: String,
    /** Normalized base URL such as `http://192.168.1.1`. */
    val url: String,
    val username: String,
    /** Password encrypted by [SecretCipher]. */
    val passwordCipher: String,
    /** SHA-256 fingerprint of the pinned certificate; null for plain HTTP. */
    val certSha256: String? = null,
)

/** [unmanagedAps]: MACs of devices the user marked as access points that are not registered nodes. */
@Serializable
data class SiteConfig(
    val id: String,
    val name: String,
    val nodes: List<NodeConfig> = emptyList(),
    val unmanagedAps: Set<String> = emptySet(),
)

private val sitesJson = Json { ignoreUnknownKeys = true }
private val sitesSerializer = ListSerializer(SiteConfig.serializer())

fun decodeSites(text: String?): List<SiteConfig> =
    if (text.isNullOrEmpty()) emptyList() else sitesJson.decodeFromString(sitesSerializer, text)

fun encodeSites(sites: List<SiteConfig>): String = sitesJson.encodeToString(sitesSerializer, sites)

/** The stored selection if it still exists, otherwise the first site. */
fun resolveSelected(sites: List<SiteConfig>, stored: String?): String? =
    sites.firstOrNull { it.id == stored }?.id ?: sites.firstOrNull()?.id

fun upserted(sites: List<SiteConfig>, site: SiteConfig): List<SiteConfig> =
    if (sites.any { it.id == site.id }) sites.map { if (it.id == site.id) site else it } else sites + site

fun withoutNode(sites: List<SiteConfig>, siteId: String, nodeId: String): List<SiteConfig> =
    sites.map { site -> if (site.id == siteId) site.copy(nodes = site.nodes.filterNot { it.id == nodeId }) else site }

fun withUnmanagedAp(sites: List<SiteConfig>, siteId: String, mac: String, isAp: Boolean): List<SiteConfig> =
    sites.map { site ->
        if (site.id != siteId) site
        else site.copy(unmanagedAps = if (isAp) site.unmanagedAps + mac.lowercase() else site.unmanagedAps - mac.lowercase())
    }
