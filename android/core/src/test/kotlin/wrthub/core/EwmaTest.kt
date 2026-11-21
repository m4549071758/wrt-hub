package wrthub.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import wrthub.core.metrics.ewma

class EwmaTest {
    @Test
    fun `first sample is taken as is`() = assertEquals(42.0, ewma(null, 42.0, 0.3))

    @Test
    fun `moves toward the sample by alpha`() = assertEquals(50.0, ewma(100.0, 0.0, 0.5))

    @Test
    fun `rejects alpha outside 0 to 1`() {
        assertFailsWith<IllegalArgumentException> { ewma(1.0, 2.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { ewma(1.0, 2.0, 1.5) }
    }
}
