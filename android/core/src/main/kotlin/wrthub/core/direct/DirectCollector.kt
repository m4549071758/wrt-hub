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

private val MISSING_CODES = setOf(
    UbusException.METHOD_NOT_FOUND,
    UbusException.NOT_FOUND,
    UbusException.RPC_OBJECT_NOT_FOUND,
    UbusException.RPC_METHOD_NOT_FOUND,
)

private val DENIED_CODES = setOf(UbusException.PERMISSION_DENIED, UbusException.RPC_ACCESS_DENIED)

/**
 * Collects one node over ubus using only calls that LuCI's own rpcd ACLs grant, so a stock LuCI image works with
 * the root login. Calls whose object is not installed yield null; calls the ACL refuses yield null and are listed
 * in [Snapshot.deniedCalls]; every other failure propagates.
 */
class DirectCollector(private val ubus: UbusCaller, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun collect(): Snapshot {
        val denied = mutableListOf<String>()

        suspend fun optional(obj: String, method: String, args: JsonObject = JsonObject(emptyMap())): JsonObject? =
            try {
                ubus.call(obj, method, args)
            } catch (e: UbusException) {
                when (e.code) {
                    in MISSING_CODES -> null
                    in DENIED_CODES -> null.also { denied += "$obj.$method" }
                    else -> throw e
                }
            }

        val ifnames = optional("luci-rpc", "getWirelessDevices")?.let(::wirelessIfnames)
            ?: optional("iwinfo", "devices")?.arr("devices").orEmpty().mapNotNull { it.asString() }
        return Snapshot(
            schemaVersion = SNAPSHOT_SCHEMA_VERSION,
            collectedAt = clock(),
            board = required("system", "board"),
            info = required("system", "info"),
            interfaces = required("network.interface", "dump"),
            devices = required("network.device", "status"),
            wireless = ifnames.map { wireless(it) },
            dhcpLeases = optional("luci-rpc", "getDHCPLeases"),
            hostHints = optional("luci-rpc", "getHostHints"),
            usteerClients = optional("usteer", "get_clients"),
            nlbw = optional("file", "exec", NLBW_ARGS)?.let(::nlbwOutput),
            blockedClients = optional("uci", "get", buildJsonObject { put("config", "firewall") })?.let(::blockedMacs).orEmpty(),
            deniedCalls = denied.toList(),
        )
    }

    private suspend fun required(obj: String, method: String): JsonObject =
        ubus.call(obj, method) ?: throw UbusException(UbusException.NO_DATA, "$obj.$method returned no data")

    private suspend fun wireless(ifname: String): WirelessRaw {
        val device = buildJsonObject { put("device", ifname) }
        val info = ubus.call("iwinfo", "info", device)
            ?: throw UbusException(UbusException.NO_DATA, "iwinfo.info returned no data for $ifname")
        val assoclist = ubus.call("iwinfo", "assoclist", device) ?: JsonObject(mapOf("results" to JsonArray(emptyList())))
        return WirelessRaw(ifname, info, assoclist)
    }
}

/** Only the interface names are kept: the rest of the response is the wireless config, including the Wi-Fi keys. */
private fun wirelessIfnames(devices: JsonObject): List<String> =
    devices.values.flatMap { radio -> radio.asObject()?.arr("interfaces").orEmpty() }
        .mapNotNull { it.asObject()?.str("ifname") }

private val NLBW_ARGS = buildJsonObject {
    put("command", NLBW_PATH)
    putJsonArray("params") { listOf("-c", "json", "-g", "mac").forEach { add(it) } }
}

private fun nlbwOutput(result: JsonObject): JsonObject? {
    if (result.int("code") != 0) return null
    return result.str("stdout")?.let { Json.parseToJsonElement(it) as? JsonObject }
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
