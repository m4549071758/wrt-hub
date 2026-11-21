package wrthub.core.direct

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import wrthub.core.json.arr
import wrthub.core.json.asObject
import wrthub.core.json.asString
import wrthub.core.json.int
import wrthub.core.json.obj
import wrthub.core.json.str
import wrthub.core.snapshot.SNAPSHOT_SCHEMA_VERSION
import wrthub.core.snapshot.Snapshot
import wrthub.core.snapshot.WirelessRaw
import wrthub.core.ubus.UbusCaller
import wrthub.core.ubus.UbusException
import wrthub.core.ubus.call

const val BLOCK_SECTION_PREFIX = "wrthub_block_"
private const val NLBW_PATH = "/usr/sbin/nlbw"

private val ABSENT_CODES = setOf(
    UbusException.METHOD_NOT_FOUND,
    UbusException.NOT_FOUND,
    UbusException.PERMISSION_DENIED,
    UbusException.RPC_OBJECT_NOT_FOUND,
    UbusException.RPC_METHOD_NOT_FOUND,
    UbusException.RPC_ACCESS_DENIED,
)

/** Runs [block] and maps "not installed / not permitted" to null; every other failure propagates. */
internal suspend fun <T> optional(block: suspend () -> T?): T? =
    try {
        block()
    } catch (e: UbusException) {
        if (e.code in ABSENT_CODES) null else throw e
    }

class DirectCollector(private val ubus: UbusCaller, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun collect(): Snapshot {
        val ifnames = optional { ubus.call("iwinfo", "devices") }?.arr("devices").orEmpty().mapNotNull { it.asString() }
        return Snapshot(
            schemaVersion = SNAPSHOT_SCHEMA_VERSION,
            collectedAt = clock(),
            board = required("system", "board"),
            info = required("system", "info"),
            interfaces = required("network.interface", "dump"),
            devices = required("network.device", "status"),
            wireless = ifnames.map { wireless(it) },
            dhcpLeases = optional { ubus.call("luci-rpc", "getDHCPLeases") },
            hostHints = optional { ubus.call("luci-rpc", "getHostHints") },
            usteerClients = optional { ubus.call("usteer", "get_clients") },
            nlbw = optional { nlbw() },
            blockedClients = optional { ubus.call("uci", "get", buildJsonObject { put("config", "firewall") }) }
                ?.let(::blockedMacs).orEmpty(),
        )
    }

    private suspend fun required(obj: String, method: String): JsonObject =
        ubus.call(obj, method) ?: throw UbusException(UbusException.NO_DATA, "$obj.$method returned no data")

    private suspend fun wireless(ifname: String): WirelessRaw {
        val device = buildJsonObject { put("device", ifname) }
        val info = ubus.call("iwinfo", "info", device)
            ?: throw UbusException(UbusException.NO_DATA, "iwinfo.info returned no data for $ifname")
        val assoclist = ubus.call("iwinfo", "assoclist", device) ?: JsonObject(mapOf("results" to JsonArray(emptyList())))
        val hostapd = if (info.str("mode") == "Master") optional { ubus.call("hostapd.$ifname", "get_clients") } else null
        return WirelessRaw(ifname, info, assoclist, hostapd)
    }

    private suspend fun nlbw(): JsonObject? {
        val args = buildJsonObject {
            put("command", NLBW_PATH)
            putJsonArray("params") { listOf("-c", "json", "-g", "mac").forEach { add(it) } }
        }
        val result = ubus.call("file", "exec", args) ?: return null
        if (result.int("code") != 0) return null
        return result.str("stdout")?.let { Json.parseToJsonElement(it) as? JsonObject }
    }
}

fun blockedMacs(uciGet: JsonObject): List<String> =
    uciGet.obj("values")?.entries.orEmpty()
        .filter { (name, _) -> name.startsWith(BLOCK_SECTION_PREFIX) }
        .flatMap { (_, section) ->
            when (val mac = section.asObject()?.get("src_mac")) {
                is JsonArray -> mac.mapNotNull { it.asString() }
                null -> emptyList()
                else -> listOfNotNull(mac.asString())
            }
        }
        .map { it.lowercase() }
        .sorted()
