package com.example.photocategorycamera.domain

import kotlin.math.hypot
import kotlin.math.sqrt

/** Select one central page; do not let a briefly stronger neighbouring page steal its identity. */
object DocumentPageSelection {
    fun centrality(quad: DocumentQuad): Float {
        val x = quad.points.sumOf { it.x.toDouble() }.toFloat() / 4
        val y = quad.points.sumOf { it.y.toDouble() }.toFloat() / 4
        val distance = hypot(x - 0.5f, y - 0.5f)
        return sqrt(quad.area) * (1.4f - 2f * distance).coerceAtLeast(0.1f)
    }

    fun distance(first: DocumentQuad, second: DocumentQuad): Float =
        first.points.zip(second.points).maxOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) }

    fun <T> select(candidates: List<T>, preferred: DocumentQuad?, quad: (T) -> DocumentQuad, score: (T) -> Float): T? {
        val matching = preferred?.let { hint -> candidates.filter { distance(quad(it), hint) <= 0.08f } }.orEmpty()
        return (matching.ifEmpty { candidates }).maxByOrNull(score)
    }
}
