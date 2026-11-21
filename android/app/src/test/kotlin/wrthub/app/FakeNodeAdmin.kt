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
import wrthub.core.admin.DhcpRange
import wrthub.core.admin.DhcpRangeInput
import wrthub.core.admin.Led
import wrthub.core.admin.LedInput
import wrthub.core.admin.RadioCapabilities
import wrthub.core.admin.RadioInput
import wrthub.core.admin.SystemConfig
import wrthub.core.admin.SystemInput
import wrthub.core.admin.WanConfig
import wrthub.core.admin.WanInput
import wrthub.core.admin.WirelessConfig
import wrthub.core.admin.PackageInfo
import wrthub.core.admin.PackageResult
import wrthub.core.admin.Storage
import wrthub.core.direct.WriteOutcome

/** Serves fixed data and records every write as "<verb> <argument>" in [log]; [writeFailure] makes writes throw. */
class FakeNodeAdmin(
    var dhcp: DhcpConfig = DhcpConfig(servesDhcp = true, leases = emptyList()),
    var forwards: List<PortForward> = emptyList(),
    var services: List<Service> = emptyList(),
    private val logs: List<LogLine> = emptyList(),
    private val writeFailure: Exception? = null,
    private val outcome: WriteOutcome = WriteOutcome.APPLIED,
    var wirelessConfig: WirelessConfig = WirelessConfig(emptyList(), emptyList()),
    var capabilities: RadioCapabilities = RadioCapabilities(emptyList(), emptyList(), emptyList()),
    var ranges: List<DhcpRange> = emptyList(),
    var wanConfig: WanConfig? = null,
    var systemConfig: SystemConfig = SystemConfig("cfg01", "OpenWrt", "UTC", true, emptyList()),
    var ledList: List<Led> = emptyList(),
    var cron: String = "",
    var installed: List<PackageInfo> = emptyList(),
    var available: List<PackageInfo> = emptyList(),
    var packageResult: PackageResult = PackageResult(0, "OK", ""),
    var storageInfo: Storage? = null,
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

    override suspend fun wireless() = wirelessConfig

    override suspend fun radioCapabilities(radio: String) = capabilities

    override suspend fun saveRadio(name: String, input: RadioInput) = write("radio $name $input")

    override suspend fun setSsidEnabled(section: String, enabled: Boolean) = write("ssid $section $enabled")

    override suspend fun dhcpRanges() = ranges

    override suspend fun saveDhcpRange(section: String, input: DhcpRangeInput) = write("range $section $input")

    override suspend fun wan() = wanConfig

    override suspend fun saveWan(input: WanInput) = write("wan $input")

    override suspend fun system() = systemConfig

    override suspend fun timezones() = mapOf("Asia/Tokyo" to "JST-9", "UTC" to "UTC")

    override suspend fun saveSystem(input: SystemInput) = write("system $input")

    override suspend fun leds() = ledList

    override suspend fun saveLed(name: String, input: LedInput) = write("led $name $input")

    override suspend fun crontab() = cron

    override suspend fun saveCrontab(text: String) {
        write("cron $text")
    }

    override suspend fun installedPackages() = installed

    /** How often the large available list was fetched. */
    var availableCalls = 0

    override suspend fun availablePackages() = available.also { availableCalls++ }

    override suspend fun updatePackageLists() = packageResult.also { write("update lists") }

    override suspend fun installPackage(name: String) = packageResult.also { write("install $name") }

    override suspend fun removePackage(name: String) = packageResult.also { write("remove $name") }

    override suspend fun storage() = storageInfo
}
