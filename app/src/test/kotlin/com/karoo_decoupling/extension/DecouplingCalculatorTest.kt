package com.karoo_decoupling.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecouplingCalculatorTest {

    @Test
    fun `returns null before 120 seconds of data`() {
        val calc = DecouplingCalculator()
        for (s in 0..60) calc.addSample(s, power = 200.0, hr = 150.0)
        assertNull(calc.result())
    }

    @Test
    fun `flat power and hr yield zero drift`() {
        val calc = DecouplingCalculator()
        for (s in 0..240) calc.addSample(s, power = 200.0, hr = 150.0)
        val r = calc.result()
        assertNotNull(r)
        assertEquals(0.0, r!!.driftPct, 1e-6)
        assertEquals(r.efFirst, r.efSecond, 1e-6)
    }

    @Test
    fun `rising hr with flat power yields positive drift`() {
        val calc = DecouplingCalculator()
        // First half: HR=140, second half: HR=160, power flat at 200W.
        for (s in 0..120) calc.addSample(s, power = 200.0, hr = 140.0)
        for (s in 121..240) calc.addSample(s, power = 200.0, hr = 160.0)
        val r = calc.result()!!
        assertTrue("drift should be positive, was ${r.driftPct}", r.driftPct > 0.0)
        // EF1 = 200/140 ~= 1.4286, EF2 = 200/160 = 1.25, drift ~= 12.5%
        assertEquals(12.5, r.driftPct, 0.1)
    }

    @Test
    fun `rising power with flat hr yields negative drift`() {
        val calc = DecouplingCalculator()
        for (s in 0..120) calc.addSample(s, power = 180.0, hr = 150.0)
        for (s in 121..240) calc.addSample(s, power = 220.0, hr = 150.0)
        val r = calc.result()!!
        assertTrue("drift should be negative, was ${r.driftPct}", r.driftPct < 0.0)
        // EF1 = 180/150 = 1.2, EF2 = 220/150 ~= 1.4667 — drift = (1.2-1.4667)/1.2 ~= -22.2%
        assertEquals(-22.2, r.driftPct, 0.5)
    }

    @Test
    fun `reset clears samples`() {
        val calc = DecouplingCalculator()
        for (s in 0..240) calc.addSample(s, power = 200.0, hr = 150.0)
        assertNotNull(calc.result())
        calc.reset()
        assertNull(calc.result())
    }

    @Test
    fun `duplicate second is ignored`() {
        val calc = DecouplingCalculator()
        calc.addSample(10, 100.0, 100.0)
        calc.addSample(10, 999.0, 999.0) // duplicate -> dropped
        for (s in 11..240) calc.addSample(s, 200.0, 150.0)
        val r = calc.result()
        assertNotNull(r)
    }

    @Test
    fun `non-positive hr is rejected`() {
        val calc = DecouplingCalculator()
        calc.addSample(0, 200.0, 0.0)  // rejected
        calc.addSample(1, 200.0, -5.0) // rejected
        for (s in 2..240) calc.addSample(s, 200.0, 150.0)
        assertNotNull(calc.result())
    }

    // --- Layer 1 additions ---

    @Test
    fun `exactly 119 seconds returns null but 120 returns a result`() {
        val below = DecouplingCalculator()
        for (s in 0..119) below.addSample(s, 200.0, 150.0)
        assertNull("totalSec=119 must still be null", below.result())

        val at = DecouplingCalculator()
        for (s in 0..120) at.addSample(s, 200.0, 150.0)
        assertNotNull("totalSec=120 must produce a result", at.result())
    }

    @Test
    fun `samples only in second half return null`() {
        // totalSec=240 -> mid=120. All samples > 120 -> n1 == 0.
        val calc = DecouplingCalculator()
        for (s in 130..240) calc.addSample(s, 200.0, 150.0)
        assertNull(calc.result())
    }

    @Test
    fun `zero power in first half is handled without divide-by-zero`() {
        val calc = DecouplingCalculator()
        for (s in 0..120) calc.addSample(s, power = 0.0, hr = 150.0)
        for (s in 121..240) calc.addSample(s, power = 200.0, hr = 150.0)
        // avgP1 = 0 -> ef1 = 0 -> guard returns null instead of NaN/Infinity.
        assertNull(calc.result())
    }

    @Test
    fun `out-of-order timestamps are dropped`() {
        val calc = DecouplingCalculator()
        for (s in 0..240) calc.addSample(s, 200.0, 150.0)
        val baseline = calc.result()!!
        // Re-adding earlier seconds with wildly different values must not affect the result.
        calc.addSample(50, 9999.0, 9999.0)
        calc.addSample(100, -100.0, -100.0)
        val after = calc.result()!!
        assertEquals(baseline.efFirst, after.efFirst, 1e-9)
        assertEquals(baseline.efSecond, after.efSecond, 1e-9)
        assertEquals(baseline.driftPct, after.driftPct, 1e-9)
    }

    @Test
    fun `four hour ride stays finite and matches hand-computed drift`() {
        // 4 hours = 14_400 samples. First half flat 200W/140bpm, second half 200W/160bpm.
        val calc = DecouplingCalculator()
        for (s in 0..7199) calc.addSample(s, 200.0, 140.0)
        for (s in 7200..14_400) calc.addSample(s, 200.0, 160.0)
        val r = calc.result()!!
        assertTrue("drift must be finite, was ${r.driftPct}", r.driftPct.isFinite())
        assertEquals(12.5, r.driftPct, 0.1)
    }
}
