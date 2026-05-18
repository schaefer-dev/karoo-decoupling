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
}
