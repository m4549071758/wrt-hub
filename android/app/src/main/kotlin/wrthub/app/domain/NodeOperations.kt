package wrthub.app.domain

import wrthub.core.direct.DirectOperations
import wrthub.core.direct.ExecResult
import wrthub.core.direct.WifiStage
import wrthub.core.direct.WriteOutcome

/** Write and diagnostic operations on one node; the hub mode will provide its own implementation. */
interface NodeOperations {
    suspend fun block(mac: String): WriteOutcome

    suspend fun unblock(mac: String): WriteOutcome

    suspend fun ping(host: String): ExecResult

    suspend fun traceroute(host: String): ExecResult

    suspend fun reboot()

    /** Stages only the AP interfaces whose SSID or key differ; apply only when [WifiStage.changed] is non-zero. */
    suspend fun stageWifi(ssid: String, key: String): WifiStage

    suspend fun applyUnchecked()

    suspend fun discardWifi()
}

class DirectNodeOperations(private val operations: DirectOperations) : NodeOperations {
    override suspend fun block(mac: String) = operations.blockClient(mac)

    override suspend fun unblock(mac: String) = operations.unblockClient(mac)

    override suspend fun ping(host: String) = operations.ping(host)

    override suspend fun traceroute(host: String) = operations.traceroute(host)

    override suspend fun reboot() = operations.reboot()

    override suspend fun stageWifi(ssid: String, key: String) = operations.stageWifi(ssid, key)

    override suspend fun applyUnchecked() = operations.applyUnchecked()

    override suspend fun discardWifi() = operations.discardWifi()
}
