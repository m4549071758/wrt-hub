package wrthub.core.admin

import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeToSequence
import wrthub.core.json.arr
import wrthub.core.json.asObject
import wrthub.core.json.int
import wrthub.core.json.long
import wrthub.core.json.str

/** One entry of `apk query --format json`, keeping only what the screens show. */
@Serializable
data class PackageInfo(
    val name: String,
    val version: String,
    val description: String? = null,
    @SerialName("installed-size") val installedSize: Long? = null,
    val depends: List<String> = emptyList(),
)

private val apkJson = Json { ignoreUnknownKeys = true }

/**
 * Reads a JSON array of packages element by element: the available list is over 10 MB, so the whole document is
 * never held, only the fields of [PackageInfo]. Sorted by name, one entry per name.
 */
@OptIn(ExperimentalSerializationApi::class)
fun parsePackages(stream: InputStream): List<PackageInfo> =
    apkJson.decodeToSequence(stream, PackageInfo.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)
        .distinctBy { it.name }
        .sortedBy { it.name }
        .toList()

private val PACKAGE_NAME = Regex("^[a-z0-9][a-z0-9._+-]{0,127}$")

/** Package names go onto apk's command line, so only plain names pass (no options, no paths, no versions). */
fun requireValidPackageName(name: String): String {
    require(PACKAGE_NAME.matches(name)) { "invalid package name: $name" }
    return name
}

private val ESSENTIAL_PACKAGES = setOf(
    "rpcd", "uhttpd", "luci-base", "luci-app-package-manager", "dropbear",
    "base-files", "busybox", "kernel", "libc", "netifd", "procd", "ubus", "ubusd", "uci",
)
private val ESSENTIAL_PREFIXES = listOf("rpcd-mod-", "apk-", "uhttpd-mod-")

/** Removing these cuts the app off the node or keeps it from booting; the app never offers it. */
fun isEssentialPackage(name: String): Boolean = name in ESSENTIAL_PACKAGES || ESSENTIAL_PREFIXES.any { name.startsWith(it) }

/** What `package-manager-call install|remove|update` reports; [code] 0 is success. */
data class PackageResult(val code: Int, val stdout: String, val stderr: String)

fun parsePackageResult(output: String): PackageResult {
    val o = (apkJson.parseToJsonElement(output.ifBlank { "{}" }) as? JsonObject) ?: JsonObject(emptyMap())
    return PackageResult(o.int("code") ?: -1, o.str("stdout").orEmpty(), o.str("stderr").orEmpty())
}

/** Free space where packages land: `/overlay` on flash devices, `/` where the root is writable (x86). */
data class Storage(val mount: String, val sizeBytes: Long, val availableBytes: Long)

fun parseStorage(mountPoints: JsonObject): Storage? {
    val mounts = mountPoints.arr("result").orEmpty().mapNotNull { it.asObject() }
    val pick = mounts.firstOrNull { it.str("mount") == "/overlay" } ?: mounts.firstOrNull { it.str("mount") == "/" } ?: return null
    return Storage(pick.str("mount")!!, pick.long("size") ?: return null, pick.long("avail") ?: return null)
}
