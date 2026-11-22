package wrthub.app.ui.clients

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import wrthub.app.data.NodeConfig
import wrthub.app.data.SiteConfig
import wrthub.app.domain.NodeOperations
import wrthub.app.domain.SiteSession
import wrthub.app.domain.dhcpServerOf
import wrthub.app.domain.routerOf
import wrthub.app.domain.siteTopology
import wrthub.core.direct.WriteOutcome
import wrthub.core.topology.Presence
import wrthub.core.topology.SiteClient
import wrthub.core.ubus.UbusException

enum class ClientFilter { ALL, WIRELESS, OTHER }

sealed interface ClientNotice {
    data class Done(val mac: String, val blocked: Boolean, val outcome: WriteOutcome) : ClientNotice

    data class Failed(val mac: String, val reason: String) : ClientNotice

    data object NoRouter : ClientNotice
}

data class ClientsUiState(
    val site: SiteConfig? = null,
    val nodeLabels: Map<String, String> = emptyMap(),
    val clients: List<SiteClient> = emptyList(),
    val loading: Boolean = false,
    val filter: ClientFilter = ClientFilter.ALL,
    val busy: Set<String> = emptySet(),
    val notice: ClientNotice? = null,
    /** The node whose DHCP server hands out static leases; null hides "fix this IP". */
    val dhcpNodeId: String? = null,
    val staticMacs: Set<String> = emptySet(),
    val sites: List<SiteConfig> = emptyList(),
)

/** Blocks and unblocks go to the router (the node with WAN), whose firewall forwards every client's traffic. */
class ClientsViewModel(
    private val session: SiteSession,
    private val operationsFor: (NodeConfig) -> NodeOperations,
) : ViewModel() {
    private data class Local(val filter: ClientFilter = ClientFilter.ALL, val busy: Set<String> = emptySet(), val notice: ClientNotice? = null)

    private val local = MutableStateFlow(Local())

    val state: StateFlow<ClientsUiState> = combine(session.state, local) { site, ui ->
        val dhcp = dhcpServerOf(site.nodes)
        ClientsUiState(
            site = site.site,
            nodeLabels = site.nodes.associate { it.config.id to it.config.label },
            clients = siteTopology(site.nodes).clients.filter { ui.filter.matches(it) },
            loading = site.loading,
            filter = ui.filter,
            busy = ui.busy,
            notice = ui.notice,
            dhcpNodeId = dhcp?.first?.config?.id,
            staticMacs = dhcp?.second?.leases.orEmpty().flatMap { it.macs }.toSet(),
            sites = site.sites,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ClientsUiState())

    fun select(siteId: String) = session.select(siteId)

    fun setUnmanagedAp(mac: String, isAp: Boolean) = session.setUnmanagedAp(mac, isAp)

    fun setFilter(filter: ClientFilter) = local.update { it.copy(filter = filter) }

    fun refresh() = session.refresh()

    fun dismissNotice() = local.update { it.copy(notice = null) }

    fun setBlocked(mac: String, blocked: Boolean) {
        val router = routerOf(session.state.value.nodes)
            ?: return local.update { it.copy(notice = ClientNotice.NoRouter) }
        local.update { it.copy(busy = it.busy + mac, notice = null) }
        viewModelScope.launch {
            val operations = operationsFor(router.config)
            val notice = try {
                ClientNotice.Done(mac, blocked, if (blocked) operations.block(mac) else operations.unblock(mac))
            } catch (e: IOException) {
                ClientNotice.Failed(mac, e.message ?: e.javaClass.simpleName)
            } catch (e: UbusException) {
                ClientNotice.Failed(mac, e.message ?: "ubus status ${e.code}")
            }
            session.refresh()
            local.update { it.copy(busy = it.busy - mac, notice = notice) }
        }
    }
}

private fun ClientFilter.matches(client: SiteClient): Boolean = when (this) {
    ClientFilter.ALL -> true
    ClientFilter.WIRELESS -> client.presence == Presence.WIRELESS
    ClientFilter.OTHER -> client.presence != Presence.WIRELESS
}
