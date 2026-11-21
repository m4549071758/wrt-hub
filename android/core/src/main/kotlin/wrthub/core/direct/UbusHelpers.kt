package wrthub.core.direct

import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import wrthub.core.json.int
import wrthub.core.json.str
import wrthub.core.json.obj
import wrthub.core.ubus.UbusCaller
import wrthub.core.ubus.UbusException
import wrthub.core.ubus.call

/** This rpcd session already holds staged changes the app did not just make; applying now would apply them too. */
class PendingChangesException(val configs: Set<String>) :
    IllegalStateException("uci changes already staged in this session: ${configs.sorted().joinToString()}")

internal suspend fun UbusCaller.requireNoPendingChanges() {
    val configs = call("uci", "changes")?.obj("changes")?.filterValues { (it as? JsonArray)?.isNotEmpty() == true }?.keys.orEmpty()
    if (configs.isNotEmpty()) throw PendingChangesException(configs)
}

internal suspend fun UbusCaller.exec(command: String, params: List<String>): ExecResult {
    val args = buildJsonObject {
        put("command", command)
        putJsonArray("params") { params.forEach { add(it) } }
    }
    val result: JsonObject = call("file", "exec", args)
        ?: throw UbusException(UbusException.NO_DATA, "file.exec returned no data for $command")
    return ExecResult(result.int("code") ?: -1, result.str("stdout").orEmpty(), result.str("stderr").orEmpty())
}

/**
 * Applies staged changes with rpcd's rollback timer, then confirms only if the router still answers.
 * An exception after apply means the confirm did not happen, so rpcd reverts the change by itself.
 */
internal suspend fun UbusCaller.applyWithRollback(rollbackSeconds: Int): WriteOutcome {
    call(
        "uci",
        "apply",
        buildJsonObject {
            put("rollback", true)
            put("timeout", rollbackSeconds)
        },
    )
    return try {
        call("system", "info")
        call("uci", "confirm")
        WriteOutcome.APPLIED
    } catch (e: IOException) {
        WriteOutcome.ROLLED_BACK
    } catch (e: UbusException) {
        WriteOutcome.ROLLED_BACK
    }
}
