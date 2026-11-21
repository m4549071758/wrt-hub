package wrthub.app

import kotlin.test.assertEquals
import org.junit.Test
import wrthub.app.ui.formatBps
import wrthub.app.ui.formatUptime
import wrthub.app.ui.formatUsedPercent

class FormatTest {
    @Test
    fun bitrates() {
        assertEquals("999 bps", formatBps(999.0))
        assertEquals("8.0 kbps", formatBps(8000.0))
        assertEquals("5.4 Mbps", formatBps(5_368_000.0))
        assertEquals("1.00 Gbps", formatBps(1_003_176_000.0))
    }

    @Test
    fun uptime() {
        assertEquals("42d 22h", formatUptime(3_708_200))
        assertEquals("19h 12m", formatUptime(69_142))
        assertEquals("5m", formatUptime(300))
    }

    @Test
    fun `memory used percent`() {
        assertEquals("24%", formatUsedPercent(available = 384_180_224, total = 511_725_568))
        assertEquals("-", formatUsedPercent(available = 0, total = 0))
    }
}
