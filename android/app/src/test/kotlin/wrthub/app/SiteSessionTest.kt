package wrthub.app

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import wrthub.app.data.SiteConfig
import wrthub.app.domain.NodeState
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
}
