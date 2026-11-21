package wrthub.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import wrthub.core.json.str
import wrthub.core.snapshot.Snapshot
import wrthub.core.ubus.UbusCaller
import wrthub.core.ubus.UbusException

data class RecordedCall(val obj: String, val method: String, val args: JsonObject)

class FakeUbus(val handler: suspend (obj: String, method: String, args: JsonObject) -> JsonObject?) : UbusCaller {
    val calls = mutableListOf<RecordedCall>()

    override suspend fun call(obj: String, method: String, args: JsonObject): JsonObject? {
        calls += RecordedCall(obj, method, args)
        return handler(obj, method, args)
    }
}

fun notFound(): Nothing = throw UbusException(UbusException.NOT_FOUND, "not found")

/** A router whose ubus answers are exactly the raw responses stored in [snapshot]. */
fun fakeRouter(snapshot: Snapshot): FakeUbus = FakeUbus { obj, method, args ->
    val device = args.str("device")
    when ("$obj.$method") {
        "system.board" -> snapshot.board
        "system.info" -> snapshot.info
        "network.interface.dump" -> snapshot.interfaces
        "network.device.status" -> snapshot.devices
        "iwinfo.devices" -> buildJsonObject { putJsonArray("devices") { snapshot.wireless.forEach { add(it.ifname) } } }
        "iwinfo.info" -> snapshot.wireless.first { it.ifname == device }.info
        "iwinfo.assoclist" -> snapshot.wireless.first { it.ifname == device }.assoclist
        "luci-rpc.getDHCPLeases" -> snapshot.dhcpLeases ?: notFound()
        "luci-rpc.getHostHints" -> snapshot.hostHints ?: notFound()
        "usteer.get_clients" -> snapshot.usteerClients ?: notFound()
        "file.exec" -> snapshot.nlbw?.let { nlbw ->
            buildJsonObject {
                put("code", 0)
                put("stdout", nlbw.toString())
            }
        } ?: notFound()
        "uci.get" -> buildJsonObject { putJsonObject("values") {} }
        else -> if (obj.startsWith("hostapd.") && method == "get_clients") {
            snapshot.wireless.firstOrNull { "hostapd.${it.ifname}" == obj }?.hostapdClients ?: notFound()
        } else {
            notFound()
        }
    }
}
