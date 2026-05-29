package com.karoo_decoupling.extension

/**
 * Live W' balance via the Skiba differential model.
 *
 *   if P  > CP:  dW'/dt = (Wmax - W') / TAU - (P - CP)   // recovery toward Wmax, minus expenditure
 *   if P <= CP:  dW'/dt = (Wmax - W') / TAU              // pure recovery toward Wmax
 *
 * W' is clamped to [0, Wmax] after each step. dt is clamped to [0, 5] s for stability
 * (so a long ELAPSED_TIME gap or a clock glitch can't blow up the integral). TAU is the
 * Skiba constant-tau recovery time constant.
 *
 * Pure math — no Android, no SDK types — fully JVM-testable. State is just the current
 * W' balance; [reset] re-initialises it to Wmax for a fresh ride.
 */
class WBalCalculator {
    private var wBal: Double = Double.NaN

    /** (Re)initialise the balance to full (Wmax). Call on a fresh ride. */
    fun reset(wMax: Double) {
        wBal = wMax
    }

    /**
     * Advance the model by [dtSec] seconds at [power] watts, given [cp] and [wMax].
     * Returns the new [WBalResult]. If the balance has not been initialised yet (or
     * [wMax] changed), it is seeded to [wMax] on first use.
     */
    fun step(dtSec: Double, power: Double, cp: Double, wMax: Double): WBalResult {
        if (wBal.isNaN()) wBal = wMax
        // A shrinking Wmax (rider edits the setting mid-ride) must not leave W' above the cap.
        if (wBal > wMax) wBal = wMax

        val dt = dtSec.coerceIn(0.0, 5.0)
        val recovery = (wMax - wBal) / TAU
        val rate = if (power > cp) recovery - (power - cp) else recovery
        wBal = (wBal + rate * dt).coerceIn(0.0, wMax)

        val pct = if (wMax > 0.0) wBal / wMax * 100.0 else 0.0
        return WBalResult(
            wBalJoules = wBal,
            wMaxJoules = wMax,
            pctRemaining = pct,
            status = WBalStatus.forPct(pct),
            rateJoulesPerSec = rate,
            secondsToBoundary = secondsToBoundary(wBal, wMax, rate),
        )
    }

    private fun secondsToBoundary(wBal: Double, wMax: Double, rate: Double): Int? = when {
        rate < -RATE_EPSILON -> (wBal / -rate).toInt()         // depleting → time to empty
        rate > RATE_EPSILON -> ((wMax - wBal) / rate).toInt()  // recovering → time to full
        else -> null                                            // steady → no meaningful ETA
    }

    companion object {
        const val TAU = 546.0

        /** Below this |rate| (J/s) the ETA is meaningless, so we report null. */
        private const val RATE_EPSILON = 0.5
    }
}

data class WBalResult(
    val wBalJoules: Double,
    val wMaxJoules: Double,
    val pctRemaining: Double,
    val status: WBalStatus,
    /** Signed dW'/dt at the last step: negative = depleting, positive = recovering. */
    val rateJoulesPerSec: Double,
    /** Time to empty (depleting) or full (recovering); null when ~steady. */
    val secondsToBoundary: Int?,
)

/** W' remaining status bands. Boundaries belong to the higher (fresher) band. */
enum class WBalStatus {
    FRESH, GOOD, WORKING, DEPLETING, CRITICAL, EMPTY;

    companion object {
        fun forPct(pct: Double): WBalStatus = when {
            pct > 90.0 -> FRESH
            pct >= 70.0 -> GOOD
            pct >= 50.0 -> WORKING
            pct >= 30.0 -> DEPLETING
            pct >= 10.0 -> CRITICAL
            else -> EMPTY
        }
    }
}
