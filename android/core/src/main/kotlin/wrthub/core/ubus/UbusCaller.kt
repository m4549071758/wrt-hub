package wrthub.core.ubus

import kotlinx.serialization.json.JsonObject

val EMPTY_ARGS = JsonObject(emptyMap())

fun interface UbusCaller {
    suspend fun call(obj: String, method: String, args: JsonObject): JsonObject?
}

suspend fun UbusCaller.call(obj: String, method: String): JsonObject? = call(obj, method, EMPTY_ARGS)
