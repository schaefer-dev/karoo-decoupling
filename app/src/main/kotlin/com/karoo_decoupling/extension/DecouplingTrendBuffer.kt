package com.karoo_decoupling.extension

/**
 * Time-windowed history of whole-ride driftPct, keyed on moving seconds. Pure JVM, no
 * Android — fully unit-testable. Mirrors the ring-buffer shape of sk0711-graph's
 * DataBuffer: append to tail, evict the stale head.
 *
 * The caller may feed every per-second emission; [add] gates points to one per
 * [cadenceSec] so the sparkline stays cheap (~120 points over the 60-min window).
 */
class DecouplingTrendBuffer(
    private val windowSec: Int = 3600,
    private val cadenceSec: Int = 30,
) {
    data class Point(val movingSec: Int, val driftPct: Double)

    private val points = ArrayDeque<Point>()

    /**
     * Record driftPct at [movingSec]. Dropped if fewer than [cadenceSec] of moving time
     * elapsed since the last stored point, so callers can fire it on every tick. After
     * appending, points older than the [windowSec] window are evicted.
     */
    fun add(movingSec: Int, driftPct: Double) {
        val last = points.lastOrNull()
        if (last != null && movingSec - last.movingSec < cadenceSec) return
        points.addLast(Point(movingSec, driftPct))
        val cutoff = movingSec - windowSec
        while (points.isNotEmpty() && points.first().movingSec < cutoff) {
            points.removeFirst()
        }
    }

    fun snapshot(): List<Point> = points.toList()

    /**
     * Current driftPct minus the driftPct as of [lookbackSec] ago — i.e. the trailing
     * trend. Uses the newest point at or before `now - lookbackSec`. Returns null until
     * the buffer spans that far back, so callers can show a neutral state during warm-up.
     */
    fun deltaOver(lookbackSec: Int = 1200): Double? {
        val newest = points.lastOrNull() ?: return null
        val target = newest.movingSec - lookbackSec
        if (points.first().movingSec > target) return null
        val past = points.lastOrNull { it.movingSec <= target } ?: return null
        return newest.driftPct - past.driftPct
    }

    fun reset() = points.clear()
}
