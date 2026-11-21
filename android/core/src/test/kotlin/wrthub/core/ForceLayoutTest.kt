package wrthub.core

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import wrthub.core.layout.Point
import wrthub.core.layout.forceLayout

class ForceLayoutTest {
    private val star = listOf("hub" to "a", "hub" to "b", "hub" to "c", "hub" to "d")
    private val ids = listOf("hub", "a", "b", "c", "d")

    @Test
    fun `empty and single node`() {
        assertEquals(emptyMap(), forceLayout(emptyList(), emptyList(), 100.0, 100.0))
        assertEquals(mapOf("x" to Point(50.0, 40.0)), forceLayout(listOf("x"), emptyList(), 100.0, 80.0))
    }

    @Test
    fun `same input gives the same layout`() {
        assertEquals(forceLayout(ids, star, 1000.0, 800.0), forceLayout(ids, star, 1000.0, 800.0))
    }

    @Test
    fun `nodes stay inside the margins and do not overlap`() {
        val layout = forceLayout(ids, star, 1000.0, 800.0)
        for (p in layout.values) {
            assertTrue(p.x in 80.0..920.0 && p.y in 80.0..720.0, "$p")
        }
        val points = layout.values.toList()
        for (i in points.indices) for (j in i + 1 until points.size) {
            assertTrue(hypot(points[i].x - points[j].x, points[i].y - points[j].y) > 50.0)
        }
    }

    @Test
    fun `two linked nodes spread apart`() {
        val layout = forceLayout(listOf("a", "b"), listOf("a" to "b"), 1000.0, 1000.0)
        val a = layout.getValue("a")
        val b = layout.getValue("b")
        assertTrue(hypot(a.x - b.x, a.y - b.y) > 300.0)
    }
}
