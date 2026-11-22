package wrthub.app

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import wrthub.app.data.HubAuth
import wrthub.app.data.HubLink
import wrthub.app.data.NodeConfig
import wrthub.app.data.SiteConfig
import wrthub.app.domain.NodeState
import wrthub.app.domain.NodeUpdate
import wrthub.app.domain.SiteLoading
import wrthub.app.domain.SiteSession
import wrthub.core.Fixtures

@OptIn(ExperimentalCoroutinesApi::class)
class SiteSessionTest {
    private val cabin = SiteConfig("cabin", "Cabin", listOf(node("router"), node("ap")))
    private val home = SiteConfig("home", "Home", listOf(node("router")))

    private fun loader(unreachable: Set<String> = emptySet(), onLoad: () -> Unit = {}) = SiteLoading { site ->
        onLoad()
        val snapshots = if (site.id == "cabin") Fixtures.meshSite else Fixtures.wiredSite
        site.nodes.map { n ->
            if (n.id in unreachable) NodeState.Unreachable(n, "timeout") else NodeState.Online(n, snapshots.getValue(n.id))
        }
    }

    private fun repository() = FakeSiteRepository(listOf(home, cabin), "cabin")

    @Test
    fun `loads the nodes of the selected site`() = runTest {
        val session = SiteSession(repository(), loader(), appScope())
        advanceUntilIdle()
        assertEquals(cabin, session.state.value.site)
        assertEquals(listOf("router", "ap"), session.state.value.nodes.map { it.config.id })
        assertEquals(false, session.state.value.loading)
    }

    @Test
    fun `unreachable node is kept next to the reachable ones`() = runTest {
        val session = SiteSession(repository(), loader(unreachable = setOf("ap")), appScope())
        advanceUntilIdle()
        assertIs<NodeState.Online>(session.state.value.nodes[0])
        assertEquals(NodeState.Unreachable(cabin.nodes[1], "timeout"), session.state.value.nodes[1])
    }

    @Test
    fun `switching site reloads with that site's nodes`() = runTest {
        val session = SiteSession(repository(), loader(), appScope())
        advanceUntilIdle()
        session.select("home")
        advanceUntilIdle()
        assertEquals(home, session.state.value.site)
        assertEquals(listOf("router"), session.state.value.nodes.map { it.config.id })
    }

    @Test
    fun `keepFresh reloads on an interval until cancelled`() = runTest {
        var loads = 0
        val session = SiteSession(repository(), loader { loads++ }, appScope())
        advanceUntilIdle()
        val job = session.keepFresh(10_000)
        runCurrent()
        advanceTimeBy(20_001)
        assertEquals(4, loads)
        job.cancel()
        advanceTimeBy(60_000)
        assertEquals(4, loads)
    }

    @Test
    fun `marking an AP is stored without reloading the site`() = runTest {
        var loads = 0
        val repository = repository()
        val session = SiteSession(repository, loader { loads++ }, appScope())
        advanceUntilIdle()
        session.setUnmanagedAp("CE:11:22:00:00:01", true)
        advanceUntilIdle()
        assertEquals(setOf("ce:11:22:00:00:01"), session.state.value.site?.unmanagedAps)
        assertEquals(setOf("ce:11:22:00:00:01"), repository.current.single { it.id == "cabin" }.unmanagedAps)
        assertEquals(1, loads)
        assertEquals(2, session.state.value.nodes.size)
    }

    private val link = HubLink("https://hub.example/", "cabin", HubAuth.DEVICE, "enc")

    /** A hub site whose server lists [serverNodes] and pushes whatever goes into [pushes]; [opened] counts streams. */
    private class HubLoading(var serverNodes: List<NodeConfig>) : SiteLoading {
        var loads = 0
        var opened = 0
        var pushes = Channel<NodeUpdate>(Channel.UNLIMITED)

        override suspend fun load(site: SiteConfig): List<NodeState> {
            loads++
            return serverNodes.map { NodeState.Unreachable(it, "no snapshot yet") }
        }

        override fun updates(site: SiteConfig): Flow<NodeUpdate> {
            opened++
            return pushes.receiveAsFlow()
        }
    }

    @Test
    fun `a hub site's stored nodes follow the server without reloading again`() = runTest {
        val hubSite = SiteConfig("hub", "Hub", listOf(node("router")), hub = link)
        val repository = FakeSiteRepository(listOf(hubSite), "hub")
        val serverNodes = listOf(node("router", "Router"), node("ap", "Hall AP"))
        val loading = HubLoading(serverNodes)
        val session = SiteSession(repository, loading, appScope())
        advanceUntilIdle()
        assertEquals(serverNodes, repository.current.single().nodes)
        assertEquals(serverNodes, session.state.value.site?.nodes)
        assertEquals(1, loading.loads)
    }

    @Test
    fun `pushed snapshots update their node, and a broken stream reopens at the next refresh`() = runTest {
        val router = node("router")
        val loading = HubLoading(listOf(router))
        val session = SiteSession(FakeSiteRepository(listOf(SiteConfig("hub", "Hub", listOf(router), hub = link)), "hub"), loading, appScope())
        advanceUntilIdle()
        val job = session.keepFresh(30_000)
        runCurrent()
        assertEquals(1, loading.opened)

        val snapshot = Fixtures.meshSite.getValue("router")
        loading.pushes.send(NodeUpdate("router", snapshot))
        runCurrent()
        assertEquals(NodeState.Online(router, snapshot), session.state.value.nodes.single())

        loading.pushes.close(IOException("stream broke"))
        loading.pushes = Channel(Channel.UNLIMITED)
        runCurrent()
        advanceTimeBy(30_001)
        assertEquals(2, loading.opened)
        job.cancel()
    }
}
