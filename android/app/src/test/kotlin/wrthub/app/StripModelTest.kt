package wrthub.app

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import wrthub.app.domain.NodeState
import wrthub.app.domain.StripLink
import wrthub.app.domain.StripNode
import wrthub.app.domain.stripModel
import wrthub.core.Fixtures

class StripModelTest {
    private val router = NodeState.Online(node("router", "Router"), Fixtures.meshSite.getValue("router"))
    private val ap = NodeState.Online(node("ap", "Living AP"), Fixtures.meshSite.getValue("ap"))

    @Test
    fun `mesh site shows the router then the AP with both link directions`() {
        val model = stripModel(listOf(ap, router))
        assertTrue(model.wanUp)
        assertTrue(model.wanAddress!!.endsWith(".53"))
        assertEquals(StripNode("router", "Router", true), model.router)
        val segment = model.segments.single()
        assertEquals(StripNode("ap", "Living AP", true), segment.node)
        assertEquals(StripLink(downKbps = 5368, upKbps = 1003176, signal = -61), segment.link)
    }

    @Test
    fun `unreachable AP has no link`() {
        val model = stripModel(listOf(router, NodeState.Unreachable(node("ap", "Living AP"), "timeout")))
        val segment = model.segments.single()
        assertEquals(StripNode("ap", "Living AP", false), segment.node)
        assertNull(segment.link)
    }

    @Test
    fun `without a reachable router every node is a segment`() {
        val model = stripModel(listOf(NodeState.Unreachable(node("router"), "timeout"), ap))
        assertNull(model.router)
        assertEquals(listOf("router", "ap"), model.segments.map { it.node.id })
    }
}
