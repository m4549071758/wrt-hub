package wrthub.app

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import wrthub.app.data.NodeConfig
import wrthub.app.data.DirectNodeVerifier
import wrthub.app.data.KeystoreSecretCipher
import wrthub.app.data.NodeConnections
import wrthub.app.data.NodeVerifying
import wrthub.app.data.SecretCipher
import wrthub.app.data.SiteStore
import wrthub.app.domain.CounterReading
import wrthub.app.domain.DirectCounterReader
import wrthub.app.domain.DirectNodeOperations
import wrthub.app.domain.DirectNodeAdmin
import wrthub.app.domain.NodeAdmin
import wrthub.app.domain.NodeOperations
import wrthub.app.domain.SiteLoader
import wrthub.app.domain.SiteSession
import wrthub.core.direct.DirectOperations
import wrthub.core.direct.DirectAdmin
import wrthub.app.data.HubConnections
import wrthub.app.data.HubTokens
import wrthub.app.data.StoredHubTokens
import wrthub.app.domain.HubCounterReader
import wrthub.app.domain.HubNodeOperations
import wrthub.app.domain.HubSiteLoader
import wrthub.core.hub.HubClient
import wrthub.core.hub.HubUbusCaller

class WrtHubApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** Process-wide singletons; DataStore must have exactly one instance per file. */
class AppContainer(context: Context) {
    /** traceroute (20 hops x 2 s) and the hub's 25-second long-polls are the slowest calls; dead hosts still fail fast on connect. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    val siteStore = SiteStore(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("sites") })
    val cipher: SecretCipher = KeystoreSecretCipher()
    private val connections = NodeConnections(http, cipher)
    val hubTokens: HubTokens = StoredHubTokens(cipher)
    val hubs = HubConnections(http, hubTokens)
    val session = SiteSession(
        siteStore,
        SiteLoader(HubSiteLoader(hubs::client), connections::node),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    /** The hub a node is reached through, or null for a direct node. */
    private fun hubOf(node: NodeConfig): HubClient? =
        session.state.value.sites.firstOrNull { site -> site.hub != null && site.nodes.any { it.id == node.id } }?.hub?.let(hubs::client)

    val operationsFor: (NodeConfig) -> NodeOperations = { node ->
        hubOf(node)?.let { hub -> HubNodeOperations.create(hub, node.id, HubUbusCaller(hub, node.id)) }
            ?: DirectNodeOperations(DirectOperations(connections.node(node)))
    }
    val adminFor: (NodeConfig) -> NodeAdmin = { node ->
        val hub = hubOf(node)
        if (hub != null) {
            val relayed = HubUbusCaller(hub, node.id)
            DirectNodeAdmin(DirectAdmin(relayed, streamer = relayed))
        } else {
            val direct = connections.node(node)
            DirectNodeAdmin(DirectAdmin(direct, streamer = direct))
        }
    }
    private val directCounters = DirectCounterReader(connections::node)
    private val hubCounters = HubCounterReader({ node -> requireNotNull(hubOf(node)) { "${node.label} is not a hub node" } })
    val counters = CounterReading { node -> if (hubOf(node) != null) hubCounters.read(node) else directCounters.read(node) }
    val verifier: NodeVerifying = DirectNodeVerifier(http)
}
