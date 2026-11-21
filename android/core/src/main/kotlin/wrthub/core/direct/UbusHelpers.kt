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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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

/** Stop trying to confirm this long before rpcd's rollback fires, so a late confirm never races the revert. */
private const val CONFIRM_MARGIN_MS = 5_000L
private const val CONFIRM_INTERVAL_MS = 2_000L

/**
 * Applies staged changes with rpcd's rollback timer, then confirms once the router answers again and [check] passes.
 * Both are retried until shortly before the timer fires, because a radio restart can cut the phone off for a few
 * seconds; if they never succeed, the confirm does not happen and rpcd reverts the change by itself.
 */
internal suspend fun UbusCaller.applyWithRollback(rollbackSeconds: Int, check: suspend () -> Boolean = { true }): WriteOutcome {
    call(
        "uci",
        "apply",
        buildJsonObject {
            put("rollback", true)
            put("timeout", rollbackSeconds)
        },
    )
    suspend fun confirmOnce(): Boolean =
        try {
            call("system", "info")
            check() && call("uci", "confirm").let { true }
        } catch (e: IOException) {
            false
        } catch (e: UbusException) {
            false
        }
    val confirmed = withTimeoutOrNull(rollbackSeconds * 1_000L - CONFIRM_MARGIN_MS) {
        while (!confirmOnce()) delay(CONFIRM_INTERVAL_MS)
        true
    }
    return if (confirmed == true) WriteOutcome.APPLIED else WriteOutcome.ROLLED_BACK
}
