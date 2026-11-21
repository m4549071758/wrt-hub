package wrthub.app

import wrthub.app.domain.NodeOperations
import wrthub.core.direct.ExecResult
import wrthub.core.direct.WriteOutcome

/** Records every call as "<verb> <argument>" into [log], which several fakes can share to check ordering. */
class FakeNodeOperations(
    private val name: String = "node",
    val log: MutableList<String> = mutableListOf(),
    private val failure: Exception? = null,
    private val wifiInterfaces: Int = 1,
    private val execResult: ExecResult = ExecResult(0, "ok\n", ""),
) : NodeOperations {
    private fun record(entry: String) {
        failure?.let { throw it }
        log += entry
    }

    override suspend fun block(mac: String): WriteOutcome = record("block $mac").let { WriteOutcome.APPLIED }

    override suspend fun unblock(mac: String): WriteOutcome = record("unblock $mac").let { WriteOutcome.ROLLED_BACK }

    override suspend fun ping(host: String): ExecResult = record("ping $host").let { execResult }

    override suspend fun traceroute(host: String): ExecResult = record("traceroute $host").let { execResult }

    override suspend fun reboot() = record("reboot $name")

    override suspend fun stageWifi(ssid: String, key: String): Int = record("stage $name").let { wifiInterfaces }

    override suspend fun applyUnchecked() = record("apply $name")

    override suspend fun discardWifi() {
        log += "discard $name"
    }
}
