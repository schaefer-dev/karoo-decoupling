package com.karoo_decoupling.extension

import org.junit.Assert.assertEquals
import org.junit.Test

class DecouplingColorsTest {
    @Test fun `negative drift maps to blue`() {
        assertEquals(DecouplingColors.BLUE, DecouplingColors.forDrift(-0.1))
        assertEquals(DecouplingColors.BLUE, DecouplingColors.forDrift(-10.0))
    }

    @Test fun `zero to five percent maps to green`() {
        assertEquals(DecouplingColors.GREEN, DecouplingColors.forDrift(0.0))
        assertEquals(DecouplingColors.GREEN, DecouplingColors.forDrift(2.5))
        assertEquals(DecouplingColors.GREEN, DecouplingColors.forDrift(5.0))
    }

    @Test fun `five to eight percent maps to yellow`() {
        assertEquals(DecouplingColors.YELLOW, DecouplingColors.forDrift(5.01))
        assertEquals(DecouplingColors.YELLOW, DecouplingColors.forDrift(6.5))
        assertEquals(DecouplingColors.YELLOW, DecouplingColors.forDrift(8.0))
    }

    @Test fun `eight to eleven percent maps to orange`() {
        assertEquals(DecouplingColors.ORANGE, DecouplingColors.forDrift(8.01))
        assertEquals(DecouplingColors.ORANGE, DecouplingColors.forDrift(9.5))
        assertEquals(DecouplingColors.ORANGE, DecouplingColors.forDrift(11.0))
    }

    @Test fun `above eleven percent maps to red`() {
        assertEquals(DecouplingColors.RED, DecouplingColors.forDrift(11.01))
        assertEquals(DecouplingColors.RED, DecouplingColors.forDrift(20.0))
    }
}
