package wrthub.app

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import wrthub.app.domain.Rate
import wrthub.app.domain.RateMeter
import wrthub.core.model.DeviceCounters

class RateMeterTest {
    @Test
    fun `byte deltas become bits per second`() {
        val meter = RateMeter()
        assertTrue(meter.add(0, DeviceCounters(0, 0)).isEmpty())
        assertEquals(listOf(Rate(8000.0, 4000.0)), meter.add(1000, DeviceCounters(1000, 500)))
        assertEquals(Rate(4000.0, 0.0), meter.add(3000, DeviceCounters(2000, 500)).last())
    }

    @Test
    fun `counter reset restarts the baseline instead of producing a negative rate`() {
        val meter = RateMeter()
        meter.add(0, DeviceCounters(5000, 5000))
        assertTrue(meter.add(1000, DeviceCounters(10, 10)).isEmpty())
        assertEquals(listOf(Rate(80.0, 80.0)), meter.add(2000, DeviceCounters(20, 20)))
    }

    @Test
    fun `history keeps only the newest samples`() {
        val meter = RateMeter(capacity = 2)
        (0..4).forEach { meter.add(it * 1000L, DeviceCounters(it * 100L, 0)) }
        assertEquals(2, meter.add(5000, DeviceCounters(500, 0)).size)
    }
}
