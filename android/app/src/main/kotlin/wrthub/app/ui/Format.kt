package wrthub.app.ui

import java.util.Locale

fun formatBps(bps: Double): String = when {
    bps < 1e3 -> String.format(Locale.ROOT, "%.0f bps", bps)
    bps < 1e6 -> String.format(Locale.ROOT, "%.1f kbps", bps / 1e3)
    bps < 1e9 -> String.format(Locale.ROOT, "%.1f Mbps", bps / 1e6)
    else -> String.format(Locale.ROOT, "%.2f Gbps", bps / 1e9)
}

fun formatUptime(seconds: Long): String {
    val days = seconds / 86_400
    val hours = seconds % 86_400 / 3_600
    val minutes = seconds % 3_600 / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}

fun formatUsedPercent(available: Long, total: Long): String =
    if (total <= 0) "-" else "${100 * (total - available) / total}%"
