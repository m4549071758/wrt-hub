package wrthub.app

import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import org.junit.Test
import wrthub.app.data.SiteConfig
import wrthub.app.domain.NodeState
import wrthub.app.domain.SiteLoader
import wrthub.core.FakeUbus
import wrthub.core.Fixtures
import wrthub.core.fakeRouter

class SiteLoaderTest {
    @Test
    fun `one unreachable node does not hide the others`() = runTest {
        val site = SiteConfig("s", "Cabin", listOf(node("router"), node("ap")))
        val loader = SiteLoader { config ->
            if (config.id == "router") fakeRouter(Fixtures.meshSite.getValue("router")) else FakeUbus { _, _, _ -> throw IOException("timeout") }
        }
        val states = loader.load(site)
        assertEquals(listOf("router", "ap"), states.map { it.config.id })
        assertIs<NodeState.Online>(states[0])
        assertEquals(NodeState.Unreachable(site.nodes[1], "timeout"), states[1])
    }
}
