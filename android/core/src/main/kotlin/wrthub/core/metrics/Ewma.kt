package wrthub.core.metrics

fun ewma(previous: Double?, sample: Double, alpha: Double): Double {
    require(alpha > 0.0 && alpha <= 1.0) { "alpha must be in (0, 1], was $alpha" }
    return if (previous == null) sample else previous + alpha * (sample - previous)
}
