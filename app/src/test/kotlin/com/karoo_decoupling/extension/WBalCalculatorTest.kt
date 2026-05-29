package com.karoo_decoupling.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WBalCalculatorTest {

    private val cp = 250.0
    private val wMax = 20_000.0

    @Test
    fun `seeds to wMax on first step before any depletion`() {
        val calc = WBalCalculator()
        calc.reset(wMax)
        // At exactly CP with a fresh balance there is nothing to recover and nothing to spend.
        val r = calc.step(dtSec = 1.0, power = cp, cp = cp, wMax = wMax)
        assertEquals(wMax, r.wBalJoules, 1e-9)
        assertEquals(100.0, r.pctRemaining, 1e-9)
        assertEquals(WBalStatus.FRESH, r.status)
    }

    @Test
    fun `power above CP depletes W'`() {
        val calc = WBalCalculator()
        calc.reset(wMax)
        // 100 W over CP for 10 s ~= 1000 J expenditure (minus a tiny recovery term, which is
        // ~0 at full balance).
        val r = calc.step(dtSec = 10.0, power = cp + 100.0, cp = cp, wMax = wMax)
        assertTrue("should deplete below wMax, was ${r.wBalJoules}", r.wBalJoules < wMax)
        assertTrue("rate should be negative, was ${r.rateJoulesPerSec}", r.rateJoulesPerSec < 0.0)
        assertEquals(-100.0, r.rateJoulesPerSec, 1e-6) // recovery term ~0 at full
    }

    @Test
    fun `power below CP recovers toward wMax`() {
        val calc = WBalCalculator()
        calc.reset(wMax)
        // Spend first so there is room to recover.
        repeat(60) { calc.step(1.0, cp + 200.0, cp, wMax) }
        val depleted = calc.step(0.0, cp, cp, wMax).wBalJoules
        assertTrue(depleted < wMax)
        // Now soft-pedal well below CP; balance should climb back.
        val r = calc.step(dtSec = 30.0, power = 0.0, cp = cp, wMax = wMax)
        assertTrue("should recover, was ${r.wBalJoules} vs $depleted", r.wBalJoules > depleted)
        assertTrue("rate should be positive, was ${r.rateJoulesPerSec}", r.rateJoulesPerSec > 0.0)
    }

    @Test
    fun `balance never goes below zero`() {
        val calc = WBalCalculator()
        calc.reset(wMax)
        // Hammer way above CP for a long time.
        var last = wMax
        repeat(600) { last = calc.step(1.0, cp + 500.0, cp, wMax).wBalJoules }
        assertTrue("must clamp at 0, was $last", last >= 0.0)
        val r = calc.step(1.0, cp + 500.0, cp, wMax)
        assertEquals(0.0, r.wBalJoules, 1e-9)
        assertEquals(WBalStatus.EMPTY, r.status)
    }

    @Test
    fun `balance never exceeds wMax`() {
        val calc = WBalCalculator()
        calc.reset(wMax)
        // Recover from full while already full — must stay capped at wMax.
        val r = calc.step(dtSec = 100.0, power = 0.0, cp = cp, wMax = wMax)
        assertEquals(wMax, r.wBalJoules, 1e-9)
    }

    @Test
    fun `dt is clamped to 5 seconds`() {
        val big = WBalCalculator().apply { reset(wMax) }
        val clamped = WBalCalculator().apply { reset(wMax) }
        val rBig = big.step(dtSec = 1000.0, power = cp + 100.0, cp = cp, wMax = wMax)
        val rClamped = clamped.step(dtSec = 5.0, power = cp + 100.0, cp = cp, wMax = wMax)
        assertEquals(rClamped.wBalJoules, rBig.wBalJoules, 1e-9)
    }

    @Test
    fun `negative dt does nothing`() {
        val calc = WBalCalculator()
        calc.reset(wMax)
        calc.step(10.0, cp + 100.0, cp, wMax)
        val before = calc.step(0.0, cp, cp, wMax).wBalJoules
        val after = calc.step(dtSec = -50.0, power = cp + 100.0, cp = cp, wMax = wMax).wBalJoules
        assertEquals(before, after, 1e-9)
    }

    @Test
    fun `shrinking wMax mid-ride re-caps the balance`() {
        val calc = WBalCalculator()
        calc.reset(20_000.0)
        // Full at 20k, then the rider lowers W' to 15k.
        val r = calc.step(dtSec = 1.0, power = cp, cp = cp, wMax = 15_000.0)
        assertEquals(15_000.0, r.wBalJoules, 1e-9)
        assertEquals(100.0, r.pctRemaining, 1e-9)
    }

    @Test
    fun `status thresholds map at the band boundaries`() {
        assertEquals(WBalStatus.FRESH, WBalStatus.forPct(100.0))
        assertEquals(WBalStatus.FRESH, WBalStatus.forPct(90.01))
        assertEquals(WBalStatus.GOOD, WBalStatus.forPct(90.0))   // boundary -> higher band
        assertEquals(WBalStatus.GOOD, WBalStatus.forPct(70.0))
        assertEquals(WBalStatus.WORKING, WBalStatus.forPct(69.99))
        assertEquals(WBalStatus.WORKING, WBalStatus.forPct(50.0))
        assertEquals(WBalStatus.DEPLETING, WBalStatus.forPct(49.99))
        assertEquals(WBalStatus.DEPLETING, WBalStatus.forPct(30.0))
        assertEquals(WBalStatus.CRITICAL, WBalStatus.forPct(29.99))
        assertEquals(WBalStatus.CRITICAL, WBalStatus.forPct(10.0))
        assertEquals(WBalStatus.EMPTY, WBalStatus.forPct(9.99))
        assertEquals(WBalStatus.EMPTY, WBalStatus.forPct(0.0))
    }

    @Test
    fun `secondsToBoundary is time-to-empty while depleting`() {
        val calc = WBalCalculator()
        calc.reset(wMax)
        // Steady 100 W over CP -> ~ -100 J/s once recovery term is small. ETA ~ wBal/100.
        var r = calc.step(1.0, cp + 100.0, cp, wMax)
        r = calc.step(1.0, cp + 100.0, cp, wMax)
        assertTrue(r.rateJoulesPerSec < 0.0)
        val eta = r.secondsToBoundary!!
        // Roughly wBal / |rate|; just assert it's a sane positive horizon.
        assertTrue("eta should be positive, was $eta", eta > 0)
        assertEquals((r.wBalJoules / -r.rateJoulesPerSec).toInt(), eta)
    }

    @Test
    fun `secondsToBoundary is null when essentially steady`() {
        val calc = WBalCalculator()
        calc.reset(wMax)
        // At full balance and P = CP, rate ~ 0 -> no meaningful ETA.
        val r = calc.step(1.0, cp, cp, wMax)
        assertNull(r.secondsToBoundary)
    }

    @Test
    fun `at CP the balance holds steady once below full`() {
        val calc = WBalCalculator()
        calc.reset(wMax)
        repeat(30) { calc.step(1.0, cp + 200.0, cp, wMax) }
        val depleted = calc.step(0.0, cp, cp, wMax).wBalJoules
        // At exactly CP, expenditure term is zero but recovery still pulls upward, so it
        // should not fall and should drift up slightly.
        val r = calc.step(dtSec = 1.0, power = cp, cp = cp, wMax = wMax)
        assertTrue(r.wBalJoules >= depleted)
    }
}
