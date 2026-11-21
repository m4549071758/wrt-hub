package wrthub.core.layout

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

data class Point(val x: Double, val y: Double)

/** Fruchterman-Reingold layout inside [width] x [height] with a 10% margin. Deterministic for a given [seed]. */
fun forceLayout(
    ids: List<String>,
    edges: List<Pair<String, String>>,
    width: Double,
    height: Double,
    iterations: Int = 200,
    seed: Long = 1,
): Map<String, Point> {
    require(width > 0 && height > 0) { "layout area must be positive" }
    if (ids.isEmpty()) return emptyMap()
    if (ids.size == 1) return mapOf(ids[0] to Point(width / 2, height / 2))
    val margin = min(width, height) * 0.1
    val random = Random(seed)
    val index = ids.withIndex().associate { (i, id) -> id to i }
    val xs = DoubleArray(ids.size) { margin + random.nextDouble() * (width - 2 * margin) }
    val ys = DoubleArray(ids.size) { margin + random.nextDouble() * (height - 2 * margin) }
    val links = edges.mapNotNull { (a, b) ->
        val i = index[a]
        val j = index[b]
        if (i == null || j == null || i == j) null else i to j
    }
    val k = sqrt(width * height / ids.size)
    for (step in 0 until iterations) {
        val temperature = (width / 10) * (1.0 - step.toDouble() / iterations)
        val dx = DoubleArray(ids.size)
        val dy = DoubleArray(ids.size)
        for (i in ids.indices) {
            for (j in ids.indices) {
                if (i == j) continue
                val vx = xs[i] - xs[j]
                val vy = ys[i] - ys[j]
                val d = max(sqrt(vx * vx + vy * vy), 0.01)
                val force = k * k / d
                dx[i] += vx / d * force
                dy[i] += vy / d * force
            }
        }
        for ((i, j) in links) {
            val vx = xs[i] - xs[j]
            val vy = ys[i] - ys[j]
            val d = max(sqrt(vx * vx + vy * vy), 0.01)
            val force = d * d / k
            dx[i] -= vx / d * force
            dy[i] -= vy / d * force
            dx[j] += vx / d * force
            dy[j] += vy / d * force
        }
        for (i in ids.indices) {
            val length = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), 0.01)
            val move = min(length, temperature)
            xs[i] = (xs[i] + dx[i] / length * move).coerceIn(margin, width - margin)
            ys[i] = (ys[i] + dy[i] / length * move).coerceIn(margin, height - margin)
        }
    }
    return ids.withIndex().associate { (i, id) -> id to Point(xs[i], ys[i]) }
}
