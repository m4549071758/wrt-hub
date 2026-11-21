package wrthub.app.domain

import java.io.IOException
import kotlinx.coroutines.flow.first
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
import wrthub.core.direct.DirectAdmin
import wrthub.core.direct.PendingChangesException
import wrthub.core.direct.WriteOutcome
import wrthub.core.ubus.UbusException

/** The LuCI-replacement reads and writes of one node; the hub mode will provide its own implementation. */
interface NodeAdmin {
    suspend fun syslog(): List<LogLine>

    suspend fun kernelLog(): List<LogLine>

    suspend fun routes(family: IpFamily): RouteTable

    suspend fun connections(): List<Connection>

    suspend fun conntrackUsage(): ConntrackUsage?

    suspend fun services(): List<Service>

    suspend fun serviceAction(name: String, action: ServiceAction)

    suspend fun dhcpConfig(): DhcpConfig

    suspend fun saveStaticLease(section: String?, input: StaticLeaseInput): WriteOutcome

    suspend fun deleteStaticLease(section: String): WriteOutcome

    suspend fun portForwards(): List<PortForward>

    suspend fun savePortForward(section: String?, input: PortForwardInput): WriteOutcome

    suspend fun setPortForwardEnabled(section: String, enabled: Boolean): WriteOutcome

    suspend fun deletePortForward(section: String): WriteOutcome

    suspend fun wireless(): WirelessConfig

    suspend fun radioCapabilities(radio: String): RadioCapabilities

    suspend fun saveRadio(name: String, input: RadioInput): WriteOutcome

    suspend fun setSsidEnabled(section: String, enabled: Boolean): WriteOutcome

    suspend fun dhcpRanges(): List<DhcpRange>

    suspend fun saveDhcpRange(section: String, input: DhcpRangeInput): WriteOutcome

    suspend fun wan(): WanConfig?

    /** Confirmed only once the router reaches the internet again. */
    suspend fun saveWan(input: WanInput): WriteOutcome

    suspend fun system(): SystemConfig

    suspend fun timezones(): Map<String, String>

    suspend fun saveSystem(input: SystemInput): WriteOutcome

    suspend fun leds(): List<Led>

    suspend fun saveLed(name: String, input: LedInput): WriteOutcome

    suspend fun crontab(): String

    /** A plain file write without rollback. */
    suspend fun saveCrontab(text: String)

    suspend fun installedPackages(): List<PackageInfo>

    suspend fun availablePackages(): List<PackageInfo>

    suspend fun updatePackageLists(): PackageResult

    suspend fun installPackage(name: String): PackageResult

    suspend fun removePackage(name: String): PackageResult

    suspend fun storage(): Storage?
}

class DirectNodeAdmin(private val admin: DirectAdmin) : NodeAdmin {
    override suspend fun syslog() = admin.syslog()

    override suspend fun kernelLog() = admin.kernelLog()

    override suspend fun routes(family: IpFamily) = admin.routes(family)

    override suspend fun connections() = admin.connections()

    override suspend fun conntrackUsage() = admin.conntrackUsage()

    override suspend fun services() = admin.services()

    override suspend fun serviceAction(name: String, action: ServiceAction) = admin.serviceAction(name, action)

    override suspend fun dhcpConfig() = admin.dhcpConfig()

    override suspend fun saveStaticLease(section: String?, input: StaticLeaseInput) = admin.saveStaticLease(section, input)

    override suspend fun deleteStaticLease(section: String) = admin.deleteStaticLease(section)

    override suspend fun portForwards() = admin.portForwards()

    override suspend fun savePortForward(section: String?, input: PortForwardInput) = admin.savePortForward(section, input)

    override suspend fun setPortForwardEnabled(section: String, enabled: Boolean) = admin.setPortForwardEnabled(section, enabled)

    override suspend fun deletePortForward(section: String) = admin.deletePortForward(section)

    override suspend fun wireless() = admin.wireless()

    override suspend fun radioCapabilities(radio: String) = admin.radioCapabilities(radio)

    override suspend fun saveRadio(name: String, input: RadioInput) = admin.saveRadio(name, input)

    override suspend fun setSsidEnabled(section: String, enabled: Boolean) = admin.setSsidEnabled(section, enabled)

    override suspend fun dhcpRanges() = admin.dhcpRanges()

    override suspend fun saveDhcpRange(section: String, input: DhcpRangeInput) = admin.saveDhcpRange(section, input)

    override suspend fun wan() = admin.wan()

    override suspend fun saveWan(input: WanInput) = admin.saveWan(input)

    override suspend fun system() = admin.system()

    override suspend fun timezones() = admin.timezones()

    override suspend fun saveSystem(input: SystemInput) = admin.saveSystem(input)

    override suspend fun leds() = admin.leds()

    override suspend fun saveLed(name: String, input: LedInput) = admin.saveLed(name, input)

    override suspend fun crontab() = admin.crontab()

    override suspend fun saveCrontab(text: String) = admin.saveCrontab(text)

    override suspend fun installedPackages() = admin.installedPackages()

    override suspend fun availablePackages() = admin.availablePackages()

    override suspend fun updatePackageLists() = admin.updatePackageLists()

    override suspend fun installPackage(name: String) = admin.installPackage(name)

    override suspend fun removePackage(name: String) = admin.removePackage(name)

    override suspend fun storage() = admin.storage()
}

/** Why an admin call did not happen or did not finish, in the terms the screens show. */
sealed interface AdminFailure {
    data class Invalid(val reason: String) : AdminFailure

    /** The rpcd session already held staged changes, so the write was not applied. */
    data class Pending(val configs: Set<String>) : AdminFailure

    data class Unreachable(val reason: String) : AdminFailure

    /** The router's ACL refused the call. */
    data class Denied(val call: String) : AdminFailure
}

/** Runs [block], turning the failures a router call can raise into [AdminFailure]; other exceptions propagate. */
suspend fun <T> adminCall(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: PendingChangesException) {
        Result.failure(AdminException(AdminFailure.Pending(e.configs)))
    } catch (e: IllegalArgumentException) {
        Result.failure(AdminException(AdminFailure.Invalid(e.message.orEmpty())))
    } catch (e: IOException) {
        Result.failure(AdminException(AdminFailure.Unreachable(e.message ?: e.javaClass.simpleName)))
    } catch (e: UbusException) {
        val failure = when (e.code) {
            UbusException.PERMISSION_DENIED, UbusException.RPC_ACCESS_DENIED -> AdminFailure.Denied(e.message.orEmpty())
            else -> AdminFailure.Unreachable(e.message ?: "ubus status ${e.code}")
        }
        Result.failure(AdminException(failure))
    }

class AdminException(val failure: AdminFailure) : Exception(failure.toString())

val Throwable.adminFailure: AdminFailure
    get() = (this as? AdminException)?.failure ?: AdminFailure.Unreachable(message ?: javaClass.simpleName)

/** Suspends until the session holds [nodeId], reachable or not, so a screen opened before the site loads still loads. */
suspend fun SiteSession.awaitNode(nodeId: String) {
    state.first { site -> site.nodes.any { it.config.id == nodeId } }
}
