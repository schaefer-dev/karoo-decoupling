package com.karoo_decoupling.data

/**
 * Rider-configured inputs for the W' balance model. Persisted via [SettingsRepository].
 *
 * [criticalPower] of 0 means "not configured yet" — the W'bal fields show a placeholder
 * rather than computing against a bogus CP. The defaults below are sensible starting values
 * so the fields compute out of the box; a rider with a 0 stored (cleared) is still treated
 * as unconfigured.
 */
data class WBalSettings(
    val criticalPower: Int = CP_DEFAULT,    // watts
    val wPrimeMax: Int = WPRIME_DEFAULT,    // joules
) {
    val isConfigured: Boolean get() = criticalPower > 0

    companion object {
        const val CP_MIN = 50
        const val CP_MAX = 600
        const val CP_DEFAULT = 264
        const val WPRIME_MIN = 5_000
        const val WPRIME_MAX = 40_000
        const val WPRIME_DEFAULT = 21_400
    }
}
