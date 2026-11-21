package wrthub.app

import wrthub.app.domain.NodeAdmin
import wrthub.core.admin.Connection
import wrthub.core.admin.ConntrackUsage
import wrthub.core.admin.DhcpConfig
import wrthub.core.admin.IpFamily
import wrthub.core.admin.LogLine
import wrthub.core.admin.PortForward
import wrthub.core.admin.PortForwardInput
import wrthub.core.admin.RouteTable
import wrthub.core.admin.Service
import wrthub.core.admin.ServiceAction
import wrthub.core.admin.StaticLeaseInput
import wrthub.core.direct.WriteOutcome

/** Serves fixed data and records every write as "<verb> <argument>" in [log]; [writeFailure] makes writes throw. */
class FakeNodeAdmin(
    var dhcp: DhcpConfig = DhcpConfig(servesDhcp = true, leases = emptyList()),
    var forwards: List<PortForward> = emptyList(),
    var services: List<Service> = emptyList(),
    private val logs: List<LogLine> = emptyList(),
    private val writeFailure: Exception? = null,
    private val outcome: WriteOutcome = WriteOutcome.APPLIED,
) : NodeAdmin {
    val log = mutableListOf<String>()

    private fun write(entry: String): WriteOutcome {
        writeFailure?.let { throw it }
        log += entry
        return outcome
    }

    override suspend fun syslog() = logs.also { log += "read syslog" }

    override suspend fun kernelLog() = logs.also { log += "read dmesg" }

    override suspend fun routes(family: IpFamily) = RouteTable.Parsed(emptyList()).also { log += "read routes $family" }

    override suspend fun connections(): List<Connection> = emptyList()

    override suspend fun conntrackUsage(): ConntrackUsage? = null

    override suspend fun services() = services

    override suspend fun serviceAction(name: String, action: ServiceAction) {
        write("rc $name ${action.rcAction}")
    }

    override suspend fun dhcpConfig() = dhcp

    override suspend fun saveStaticLease(section: String?, input: StaticLeaseInput) = write("lease ${section ?: "new"} $input")

    override suspend fun deleteStaticLease(section: String) = write("delete lease $section")

    override suspend fun portForwards() = forwards

    override suspend fun savePortForward(section: String?, input: PortForwardInput) = write("forward ${section ?: "new"} $input")

    override suspend fun setPortForwardEnabled(section: String, enabled: Boolean) = write("enable $section $enabled")

    override suspend fun deletePortForward(section: String) = write("delete forward $section")
}
