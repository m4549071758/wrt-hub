package wrthub.app.domain

import wrthub.core.model.DeviceCounters

data class Rate(val rxBps: Double, val txBps: Double)

class RateMeter(private val capacity: Int = 60) {
    private var last: Pair<Long, DeviceCounters>? = null
    private val history = ArrayDeque<Rate>()

    /** Adds a sample taken at [timeMs]. A counter that went backwards (reboot, wrap) restarts the baseline. */
    fun add(timeMs: Long, counters: DeviceCounters): List<Rate> {
        val previous = last
        last = timeMs to counters
        if (previous != null) {
            val (previousTime, previousCounters) = previous
            val seconds = (timeMs - previousTime) / 1000.0
            if (seconds > 0 && counters.rxBytes >= previousCounters.rxBytes && counters.txBytes >= previousCounters.txBytes) {
                history.addLast(
                    Rate(
                        rxBps = (counters.rxBytes - previousCounters.rxBytes) * 8 / seconds,
                        txBps = (counters.txBytes - previousCounters.txBytes) * 8 / seconds,
                    ),
                )
                while (history.size > capacity) history.removeFirst()
            }
        }
        return history.toList()
    }
}
