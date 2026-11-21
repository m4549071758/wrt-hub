package wrthub.core.snapshot

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

const val SNAPSHOT_SCHEMA_VERSION = 1

@Serializable
data class WirelessRaw(
    val ifname: String,
    val info: JsonObject,
    val assoclist: JsonObject,
    val hostapdClients: JsonObject? = null,
)

@Serializable
data class Snapshot(
    val schemaVersion: Int,
    val collectedAt: Long,
    val board: JsonObject,
    val info: JsonObject,
    val interfaces: JsonObject,
    val devices: JsonObject,
    val wireless: List<WirelessRaw>,
    val dhcpLeases: JsonObject? = null,
    val hostHints: JsonObject? = null,
    val usteerClients: JsonObject? = null,
    val nlbw: JsonObject? = null,
    /** Raw output of `ip -4 neigh show`; text because busybox `ip` has no JSON output. */
    val neighbors: String? = null,
    /** Raw `uci get dhcp`; the static leases' names override the DHCP clients' own host names. */
    val dhcpConfig: JsonObject? = null,
    val blockedClients: List<String> = emptyList(),
    /** ubus calls (`object.method`) that the router's ACL refused, so their data is missing on purpose. */
    val deniedCalls: List<String> = emptyList(),
)

class UnsupportedSnapshotException(version: Int) :
    IllegalArgumentException("snapshot schemaVersion $version is not supported (expected $SNAPSHOT_SCHEMA_VERSION)")

@OptIn(ExperimentalSerializationApi::class)
private val snapshotJson = Json {
    explicitNulls = false
    encodeDefaults = true
}

fun decodeSnapshot(text: String): Snapshot {
    val snapshot = snapshotJson.decodeFromString(Snapshot.serializer(), text)
    if (snapshot.schemaVersion != SNAPSHOT_SCHEMA_VERSION) throw UnsupportedSnapshotException(snapshot.schemaVersion)
    return snapshot
}

fun encodeSnapshot(snapshot: Snapshot): String = snapshotJson.encodeToString(Snapshot.serializer(), snapshot)
