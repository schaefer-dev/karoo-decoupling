package com.karoo_decoupling.extension

object WBalColors {
    const val WARMUP = 0xFF424242.toInt() // unconfigured / no data yet (matches DecouplingColors)
    const val FRESH = 0xFF1B5E20.toInt()  // green
    const val GOOD = 0xFF558B2F.toInt()   // light green
    const val WORKING = 0xFFF9A825.toInt() // amber
    const val DEPLETING = 0xFFE65100.toInt() // orange
    const val CRITICAL = 0xFFB71C1C.toInt() // red
    const val EMPTY = 0xFF616161.toInt()  // grey

    fun forStatus(status: WBalStatus): Int = when (status) {
        WBalStatus.FRESH -> FRESH
        WBalStatus.GOOD -> GOOD
        WBalStatus.WORKING -> WORKING
        WBalStatus.DEPLETING -> DEPLETING
        WBalStatus.CRITICAL -> CRITICAL
        WBalStatus.EMPTY -> EMPTY
    }
}
