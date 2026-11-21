package wrthub.app

import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import wrthub.app.data.SiteConfig
import wrthub.app.domain.NodeState
import wrthub.app.domain.SiteLoading
import wrthub.app.domain.SiteSession
import wrthub.app.ui.mesh.MeshViewModel
import wrthub.core.Fixtures
import wrthub.core.json.str
import wrthub.core.snapshot.Snapshot
import wrthub.core.topology.Presence

/** Same snapshot with the expected throughput of every mesh peer replaced. */
private fun Snapshot.withMeshThroughput(kbps: Long): Snapshot = copy(
    wireless = wireless.map { w ->
        if (w.info.str("mode") != "Mesh Point") {
            w
        } else {
            val results = w.assoclist.getValue("results").jsonArray.map { JsonObject(it.jsonObject + ("thr" to JsonPrimitive(kbps))) }
            w.copy(assoclist = JsonObject(mapOf("results" to JsonArray(results))))
        }
    },
)

@OptIn(ExperimentalCoroutinesApi::class)
class MeshViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val cabin = SiteConfig("cabin", "Cabin", listOf(node("router", "WRC-1"), node("ap", "WRC-2")))

    private fun TestScope.viewModel(routerThroughput: () -> Long = { 5368 }, onLoad: () -> Unit = {}): Pair<MeshViewModel, SiteSession> {
        val loader = SiteLoading { site ->
            onLoad()
            site.nodes.map { n ->
                val snapshot = Fixtures.meshSite.getValue(n.id)
                NodeState.Online(n, if (n.id == "router") snapshot.withMeshThroughput(routerThroughput()) else snapshot)
            }
        }
        val session = SiteSession(FakeSiteRepository(listOf(cabin)), loader, appScope())
        return MeshViewModel(session, refreshIntervalMs = 10_000) to session
    }

    @Test
    fun `nodes carry their clients and the link its weaker signal`() = runTest(main.dispatcher) {
        val (vm, _) = viewModel()
        advanceUntilIdle()
        val nodes = vm.state.value.nodes.associateBy { it.id }
        assertEquals("WRC-1", nodes.getValue("router").label)
        assertEquals(3, nodes.getValue("router").clients.size)
        assertEquals(2, nodes.getValue("ap").clients.size)
        val edge = vm.state.value.edges.single()
        assertEquals("ap" to "router", edge.a to edge.b)
        assertEquals(-61, edge.signal)
    }

    @Test
    fun `link throughput is smoothed across loads`() = runTest(main.dispatcher) {
        var throughput = 1000L
        val (vm, session) = viewModel(routerThroughput = { throughput })
        advanceUntilIdle()
        assertEquals(1000.0, vm.state.value.edges.single().bToAKbps)
        throughput = 2000
        session.refresh()
        advanceUntilIdle()
        assertEquals(1300.0, vm.state.value.edges.single().bToAKbps)
        assertEquals(1003176.0, vm.state.value.edges.single().aToBKbps)
    }

    @Test
    fun `live mode reloads every interval until stopped`() = runTest(main.dispatcher) {
        var loads = 0
        val (vm, _) = viewModel(onLoad = { loads++ })
        advanceUntilIdle()
        vm.setLive(true)
        runCurrent()
        advanceTimeBy(10_001)
        assertEquals(3, loads)
        vm.setLive(false)
        advanceTimeBy(60_000)
        assertEquals(3, loads)
    }

    @Test
    fun `devices without Wi-Fi association hang under the router`() = runTest(main.dispatcher) {
        val home = SiteConfig("home", "Home", listOf(node("router", "Gateway")))
        val loader = SiteLoading { site -> site.nodes.map { NodeState.Online(it, Fixtures.wiredSite.getValue(it.id)) } }
        val vm = MeshViewModel(SiteSession(FakeSiteRepository(listOf(home)), loader, appScope()))
        advanceUntilIdle()
        val router = vm.state.value.nodes.single()
        assertEquals(48, router.clients.size)
        assertEquals(setOf(Presence.LEASE_ONLY), router.clients.map { it.presence }.toSet())
    }
}
