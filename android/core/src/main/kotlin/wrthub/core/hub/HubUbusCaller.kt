package wrthub.core.hub

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wrthub.core.ubus.CommandStreamer
import wrthub.core.ubus.UbusCaller
import wrthub.core.ubus.UbusException

/** The agent's answer to a relayed call: the ubus status, its data, and why the agent refused it if it did. */
@Serializable
private data class RelayReply(val code: Int, val data: JsonObject? = null, val error: String? = null)

/**
 * ubus over the hub: each call is relayed by the node's agent (command `ubus`), so the direct-mode code (DirectAdmin,
 * DirectOperations) runs unchanged on a hub site. Statuses come back as the same [UbusException] codes rpcd gives.
 */
class HubUbusCaller(private val hub: HubClient, private val nodeId: String) : UbusCaller, CommandStreamer {
    override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject? {
        val result = hub.run(
            nodeId,
            "ubus",
            buildJsonObject {
                put("object", obj)
                put("method", method)
                put("args", args)
            },
        ) ?: throw HubCommandException("$obj.$method: the agent sent no answer")
        val reply = hubJson.decodeFromJsonElement(RelayReply.serializer(), result)
        if (reply.code != 0) throw UbusException(reply.code, "$obj.$method: ${reply.error ?: "ubus status ${reply.code}"}")
        return reply.data
    }

    /** The output comes whole in the agent's answer; there is no cgi-io on the hub path. */
    override suspend fun <T> stream(command: String, params: List<String>, read: (InputStream) -> T): T {
        val args = buildJsonObject {
            put("command", command)
            put("params", JsonArray(params.map(::JsonPrimitive)))
        }
        val result = call("file", "exec", args) ?: throw UbusException(UbusException.NO_DATA, "file.exec returned no data for $command")
        val stdout = (result["stdout"] as? JsonPrimitive)?.content.orEmpty()
        return read(ByteArrayInputStream(stdout.toByteArray(Charsets.UTF_8)))
    }
}
