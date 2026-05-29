package com.karoo_decoupling.extension

import com.karoo_decoupling.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** % of W' remaining, colored by status. */
class WBalPercentDataType(engine: WBalEngine, extension: String) :
    WBalFieldDataType(engine, extension, TYPE_ID) {
    override val titleResId: Int = R.string.wbal_percent_field_name

    override fun formatValue(result: WBalResult): String =
        String.format(Locale.US, "%.0f%%", result.pctRemaining)

    override fun backgroundColor(result: WBalResult): Int = WBalColors.forStatus(result.status)

    companion object { const val TYPE_ID = "wbal_percent" }
}

/** Absolute W' remaining in kJ. */
class WBalRemainingDataType(engine: WBalEngine, extension: String) :
    WBalFieldDataType(engine, extension, TYPE_ID) {
    override val titleResId: Int = R.string.wbal_kj_field_name

    override fun formatValue(result: WBalResult): String =
        String.format(Locale.US, "%.1f kJ", result.wBalJoules / 1000.0)

    override fun backgroundColor(result: WBalResult): Int = WBalColors.forStatus(result.status)

    companion object { const val TYPE_ID = "wbal_kj" }
}

/** Status word (FRESH..EMPTY), colored by status. */
class WBalStatusDataType(engine: WBalEngine, extension: String) :
    WBalFieldDataType(engine, extension, TYPE_ID) {
    override val titleResId: Int = R.string.wbal_status_field_name

    override fun formatValue(result: WBalResult): String = result.status.name

    override fun backgroundColor(result: WBalResult): Int = WBalColors.forStatus(result.status)

    companion object { const val TYPE_ID = "wbal_status" }
}

/** Time to empty (depleting) or full (recovering), m:ss, or "—" when steady. */
class WBalEtaDataType(engine: WBalEngine, extension: String) :
    WBalFieldDataType(engine, extension, TYPE_ID) {
    override val titleResId: Int = R.string.wbal_eta_field_name

    // No status-based coloring for this field — use a plain black field background like the
    // standard Karoo numeric fields, both during warm-up and once computing.
    override fun backgroundColor(result: WBalResult): Int = android.graphics.Color.BLACK
    override fun warmupColor(): Int = android.graphics.Color.BLACK

    override fun formatValue(result: WBalResult): String {
        val secs = result.secondsToBoundary ?: return "—"
        return String.format(Locale.US, "%d:%02d", secs / 60, secs % 60)
    }

    companion object { const val TYPE_ID = "wbal_eta" }
}

/** Net depletion/recovery rate, signed watts (J/s). */
class WBalRateDataType(engine: WBalEngine, extension: String) :
    WBalFieldDataType(engine, extension, TYPE_ID) {
    override val titleResId: Int = R.string.wbal_rate_field_name

    // No status-based coloring for this field — use a plain black field background like the
    // standard Karoo numeric fields, both during warm-up and once computing.
    override fun backgroundColor(result: WBalResult): Int = android.graphics.Color.BLACK
    override fun warmupColor(): Int = android.graphics.Color.BLACK

    override fun formatValue(result: WBalResult): String {
        val w = result.rateJoulesPerSec.roundToInt()
        // Show a clear sign; "+" recovering, "-" depleting, "0 W" steady.
        return when {
            w > 0 -> String.format(Locale.US, "+%d W", w)
            w < 0 -> String.format(Locale.US, "-%d W", abs(w))
            else -> "0 W"
        }
    }

    companion object { const val TYPE_ID = "wbal_rate" }
}
