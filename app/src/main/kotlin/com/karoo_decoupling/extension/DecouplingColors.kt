package com.karoo_decoupling.extension

object DecouplingColors {
    const val WARMUP = 0xFF424242.toInt()
    const val BLUE = 0xFF1565C0.toInt()
    const val GREEN = 0xFF1B5E20.toInt()
    const val YELLOW = 0xFFF57F17.toInt()
    const val ORANGE = 0xFFE65100.toInt()
    const val RED = 0xFFB71C1C.toInt()

    fun forDrift(driftPct: Double): Int = when {
        driftPct < 0.0 -> BLUE
        driftPct <= 5.0 -> GREEN
        driftPct <= 8.0 -> YELLOW
        driftPct <= 11.0 -> ORANGE
        else -> RED
    }
}
