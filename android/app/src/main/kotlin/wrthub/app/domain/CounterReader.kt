package wrthub.app.domain

import kotlinx.serialization.json.JsonObject
import wrthub.app.data.NodeConfig
import wrthub.core.model.DeviceCounters
import wrthub.core.model.parseDeviceCounters
import wrthub.core.ubus.UbusCaller
import wrthub.core.ubus.call

fun interface CounterReading {
    suspend fun read(node: NodeConfig): Map<String, DeviceCounters>
}

class DirectCounterReader(private val callerFor: (NodeConfig) -> UbusCaller) : CounterReading {
    override suspend fun read(node: NodeConfig): Map<String, DeviceCounters> =
        parseDeviceCounters(callerFor(node).call("network.device", "status") ?: JsonObject(emptyMap()))
}
