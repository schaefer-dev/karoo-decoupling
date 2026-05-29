package com.karoo_decoupling.extension

import org.junit.Assert.assertEquals
import org.junit.Test

class WBalColorsTest {

    @Test
    fun `each status maps to its band color`() {
        assertEquals(WBalColors.FRESH, WBalColors.forStatus(WBalStatus.FRESH))
        assertEquals(WBalColors.GOOD, WBalColors.forStatus(WBalStatus.GOOD))
        assertEquals(WBalColors.WORKING, WBalColors.forStatus(WBalStatus.WORKING))
        assertEquals(WBalColors.DEPLETING, WBalColors.forStatus(WBalStatus.DEPLETING))
        assertEquals(WBalColors.CRITICAL, WBalColors.forStatus(WBalStatus.CRITICAL))
        assertEquals(WBalColors.EMPTY, WBalColors.forStatus(WBalStatus.EMPTY))
    }

    @Test
    fun `pct-derived status drives color end to end`() {
        assertEquals(WBalColors.FRESH, WBalColors.forStatus(WBalStatus.forPct(95.0)))
        assertEquals(WBalColors.CRITICAL, WBalColors.forStatus(WBalStatus.forPct(20.0)))
        assertEquals(WBalColors.EMPTY, WBalColors.forStatus(WBalStatus.forPct(5.0)))
    }
}
