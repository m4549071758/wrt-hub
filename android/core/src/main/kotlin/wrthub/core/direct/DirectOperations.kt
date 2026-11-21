package wrthub.core.direct

import java.io.IOException
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

enum class WriteOutcome { APPLIED, ROLLED_BACK }

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
     * Stages SSID and key on every `mode ap` wifi-iface of this node without applying them, and returns how many
     * interfaces were staged (0: no AP interface). Stage on every node of the site first, then [applyUnchecked] on
     * all of them at once: the phone doing this usually loses its Wi-Fi as soon as one node applies.
     */
    suspend fun stageWifi(ssid: String, key: String): Int {
        requireValidSsid(ssid)
        requireValidPsk(key)
        val sections = ubus.call("uci", "get", buildJsonObject { put("config", "wireless") })?.obj("values")
            ?: throw UbusException(UbusException.NO_DATA, "uci.get wireless returned no values")
        val apSections = sections.entries
            .filter { (_, value) -> value.asObject()?.let { it.str(".type") == "wifi-iface" && it.str("mode") == "ap" } == true }
            .map { it.key }
        if (apSections.isEmpty()) return 0
        for (section in apSections) {
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
        return apSections.size
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

    suspend fun ping(host: String): ExecResult = exec(PING_PATH, listOf("-c", "4", "-W", "2", requireValidHost(host)))

    suspend fun traceroute(host: String): ExecResult =
        exec(TRACEROUTE_PATH, listOf("-q", "1", "-w", "2", "-m", "20", requireValidHost(host)))

    private suspend fun exec(command: String, params: List<String>): ExecResult {
        val args = buildJsonObject {
            put("command", command)
            putJsonArray("params") { params.forEach { add(it) } }
        }
        val result: JsonObject = ubus.call("file", "exec", args)
            ?: throw UbusException(UbusException.NO_DATA, "file.exec returned no data for $command")
        return ExecResult(result.int("code") ?: -1, result.str("stdout").orEmpty(), result.str("stderr").orEmpty())
    }

    /**
     * Applies staged changes with rpcd's rollback timer, then confirms only if the router still answers.
     * An exception after apply means the confirm did not happen, so rpcd reverts the change by itself.
     */
    private suspend fun applyWithRollback(): WriteOutcome {
        ubus.call(
            "uci",
            "apply",
            buildJsonObject {
                put("rollback", true)
                put("timeout", rollbackSeconds)
            },
        )
        return try {
            ubus.call("system", "info")
            ubus.call("uci", "confirm")
            WriteOutcome.APPLIED
        } catch (e: IOException) {
            WriteOutcome.ROLLED_BACK
        } catch (e: UbusException) {
            WriteOutcome.ROLLED_BACK
        }
    }
}
