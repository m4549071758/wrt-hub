package wrthub.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.str(key: String): String? = this[key]?.asString()

fun JsonObject.long(key: String): Long? = this[key]?.asLong()

fun JsonObject.int(key: String): Int? = long(key)?.toInt()

fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

fun JsonElement.asObject(): JsonObject? = this as? JsonObject

fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonElement.asLong(): Long? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
