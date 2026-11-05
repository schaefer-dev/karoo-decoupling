package com.karoo_decoupling.extension

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class WBalDisplayTest {

    private fun result(pct: Double) = WBalResult(
        wBalJoules = pct * 200.0,
        wMaxJoules = 20_000.0,
        pctRemaining = pct,
        status = WBalStatus.forPct(pct),
        rateJoulesPerSec = -10.0,
        secondsToBoundary = 100,
    )

    private val percent: (WBalResult) -> String = { String.format(Locale.US, "%.0f%%", it.pctRemaining) }
    private val color: (WBalResult) -> Int = { WBalColors.forStatus(it.status) }

    @Test
    fun `ticks that don't change the shown value are dropped`() = runTest {
        val shown = flowOf(null, null, result(71.2), result(71.4), result(70.6), result(69.4))
            .toDisplay(simulated = false, percent, color, WBalColors.WARMUP)
            .toList()
        assertEquals(
            listOf(
                WBalDisplay("—", WBalColors.WARMUP),
                WBalDisplay("71%", WBalColors.GOOD),
                WBalDisplay("69%", WBalColors.WORKING),
            ),
            shown,
        )
    }

    @Test
    fun `simulated builds append the marker`() {
        assertEquals("— *", wbalDisplay(null, true, percent, color, WBalColors.WARMUP).text)
        assertEquals("50% *", wbalDisplay(result(50.0), true, percent, color, WBalColors.WARMUP).text)
    }
}
