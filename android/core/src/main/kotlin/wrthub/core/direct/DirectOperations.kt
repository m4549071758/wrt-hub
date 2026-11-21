package wrthub.core.direct

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import wrthub.core.json.asObject
import wrthub.core.json.int
import wrthub.core.json.obj
import wrthub.core.json.str
import wrthub.core.ubus.UbusCaller
import wrthub.core.ubus.UbusException
import wrthub.core.ubus.call

/** [UNCHANGED]: the requested values were already set, so nothing was applied. */
enum class WriteOutcome { APPLIED, ROLLED_BACK, UNCHANGED }

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

private const val PING_PATH = "/bin/ping"
private const val TRACEROUTE_PATH = "/bin/traceroute"

class DirectOperations(private val ubus: UbusCaller, private val rollbackSeconds: Int = 30) {
    init {
        require(rollbackSeconds in 10..600) { "rollbackSeconds must be 10-600, was $rollbackSeconds" }
    }

    suspend fun blockClient(mac: String): WriteOutcome {
        val normalized = normalizeMac(mac)
        ubus.call(
            "uci",
            "add",
            buildJsonObject {
                put("config", "firewall")
                put("type", "rule")
                put("name", blockSectionName(normalized))
                putJsonObject("values") {
                    put("name", "wrt-hub block $normalized")
                    put("src", "*")
                    put("dest", "*")
                    put("src_mac", normalized)
                    put("target", "REJECT")
                }
            },
        )
        return applyWithRollback()
    }

    suspend fun unblockClient(mac: String): WriteOutcome {
        val section = blockSectionName(mac)
        ubus.call(
            "uci",
            "delete",
            buildJsonObject {
                put("config", "firewall")
                put("section", section)
            },
        )
        return applyWithRollback()
    }

    /**
     * Stages SSID and key on the `mode ap` wifi-ifaces of this node whose values differ, without applying them.
     * Only differing interfaces are staged because `uci apply` fails ("No response") when nothing changed.
     * Stage on every node of the site first, then [applyUnchecked] where [WifiStage.changed] is non-zero, all at once:
     * the phone doing this usually loses its Wi-Fi as soon as one node applies.
     */
    suspend fun stageWifi(ssid: String, key: String): WifiStage {
        requireValidSsid(ssid)
        requireValidPsk(key)
        val sections = ubus.call("uci", "get", buildJsonObject { put("config", "wireless") })?.obj("values")
            ?: throw UbusException(UbusException.NO_DATA, "uci.get wireless returned no values")
        val accessPoints = sections.entries.mapNotNull { (name, value) ->
            value.asObject()?.takeIf { it.str(".type") == "wifi-iface" && it.str("mode") == "ap" }?.let { name to it }
        }
        val changed = accessPoints.filter { (_, iface) -> iface.str("ssid") != ssid || iface.str("key") != key }.map { it.first }
        for (section in changed) {
            ubus.call(
                "uci",
                "set",
                buildJsonObject {
                    put("config", "wireless")
                    put("section", section)
                    putJsonObject("values") {
                        put("ssid", ssid)
                        put("key", key)
                    }
                },
            )
        }
        return WifiStage(accessPoints = accessPoints.size, changed = changed.size)
    }

    /**
     * Applies staged changes without rollback: the phone is usually on the Wi-Fi being changed and always loses the
     * connection, which would otherwise trigger the rollback.
     */
    suspend fun applyUnchecked() {
        ubus.call("uci", "apply", buildJsonObject { put("rollback", false) })
    }

    /** Drops staged wireless changes, so a later apply in this session does not pick them up. */
    suspend fun discardWifi() {
        ubus.call("uci", "revert", buildJsonObject { put("config", "wireless") })
    }

    suspend fun reboot() {
        ubus.call("system", "reboot")
    }

    suspend fun ping(host: String): ExecResult = ubus.exec(PING_PATH, listOf("-c", "4", "-W", "2", requireValidHost(host)))

    suspend fun traceroute(host: String): ExecResult =
        ubus.exec(TRACEROUTE_PATH, listOf("-q", "1", "-w", "2", "-m", "20", requireValidHost(host)))

    private suspend fun applyWithRollback(): WriteOutcome = ubus.applyWithRollback(rollbackSeconds)
}

/** [accessPoints] AP interfaces on the node, of which [changed] were staged with new values. */
data class WifiStage(val accessPoints: Int, val changed: Int)
