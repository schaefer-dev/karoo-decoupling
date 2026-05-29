package com.karoo_decoupling.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecouplingTrendBufferTest {

    @Test
    fun `cadence gate keeps one point per 30 seconds`() {
        val buf = DecouplingTrendBuffer()
        // Feed every second for 120 s; expect points at 0, 30, 60, 90 only.
        for (s in 0..119) buf.add(s, driftPct = s.toDouble())
        val pts = buf.snapshot()
        assertEquals(listOf(0, 30, 60, 90), pts.map { it.movingSec })
    }

    @Test
    fun `evicts points older than the 60 minute window`() {
        val buf = DecouplingTrendBuffer()
        // One point per 30 s for 70 minutes of moving time.
        var s = 0
        while (s <= 70 * 60) {
            buf.add(s, driftPct = 1.0)
            s += 30
        }
        val pts = buf.snapshot()
        val newest = pts.last().movingSec
        assertTrue("oldest point must be within the 60-min window",
            pts.first().movingSec >= newest - 3600)
    }

    @Test
    fun `deltaOver is null until the buffer spans the lookback`() {
        val buf = DecouplingTrendBuffer()
        // Only 10 minutes of data; 20-min lookback can't be satisfied.
        var s = 0
        while (s <= 10 * 60) {
            buf.add(s, driftPct = 2.0)
            s += 30
        }
        assertNull(buf.deltaOver(1200))
    }

    @Test
    fun `deltaOver returns rising trend with positive sign`() {
        val buf = DecouplingTrendBuffer()
        // 30 minutes; drift climbs linearly from 0% to ~6%.
        var s = 0
        while (s <= 30 * 60) {
            buf.add(s, driftPct = s / 300.0) // +1% every 5 min
            s += 30
        }
        val delta = buf.deltaOver(1200)!!
        // Over the trailing 20 min drift rose ~4%.
        assertEquals(4.0, delta, 0.3)
        assertTrue(delta > 0)
    }

    @Test
    fun `deltaOver returns falling trend with negative sign`() {
        val buf = DecouplingTrendBuffer()
        var s = 0
        while (s <= 30 * 60) {
            buf.add(s, driftPct = 10.0 - s / 300.0) // declining
            s += 30
        }
        val delta = buf.deltaOver(1200)!!
        assertTrue(delta < 0)
    }

    @Test
    fun `reset clears the buffer`() {
        val buf = DecouplingTrendBuffer()
        for (s in 0..200) buf.add(s, driftPct = 1.0)
        buf.reset()
        assertTrue(buf.snapshot().isEmpty())
        assertNull(buf.deltaOver(1200))
    }
}
