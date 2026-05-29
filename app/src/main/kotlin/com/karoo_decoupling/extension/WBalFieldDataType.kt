package com.karoo_decoupling.extension

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.RemoteViews
import com.karoo_decoupling.R
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Shared wiring for the five single-value W'bal display fields. Each subclass only decides
 * how to turn a [WBalResult] into a line of text and which background color to use; the base
 * handles preview, collecting the shared [WBalEngine] output, and the throttled RemoteViews
 * IPC. Kept thin per the project's Calculator/Coordinator/DataType seam (see CLAUDE.md).
 */
abstract class WBalFieldDataType(
    private val engine: WBalEngine,
    extension: String,
    typeId: String,
) : DataTypeImpl(extension, typeId) {

    /** String resource for the small in-field title (matches the field's displayName). */
    protected abstract val titleResId: Int

    /** Text shown for a computed result (already includes the simulated marker handling). */
    protected abstract fun formatValue(result: WBalResult): String

    /** Background color for a computed result. Default: neutral warmup grey. */
    protected open fun backgroundColor(result: WBalResult): Int = WBalColors.WARMUP

    /** Background color before a result is available (warm-up / unconfigured). */
    protected open fun warmupColor(): Int = WBalColors.WARMUP

    /** A representative result used to render the page-editor preview. */
    protected open fun previewResult(): WBalResult = PREVIEW

    override fun startStream(emitter: Emitter<StreamState>) {
        // Graphical field — the raw-value channel is unused.
        emitter.onNext(StreamState.NotAvailable)
        emitter.setCancellable { }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Suppress Karoo's own header bar so our colored background fills the whole tile;
        // we draw a small title inside the layout instead (mirrors the built-in fields).
        emitter.onNext(UpdateGraphicConfig(showHeader = false, formatDataTypeId = null))

        if (config.preview) {
            emitter.updateView(render(context, previewResult(), simulated = false, config))
            return
        }

        engine.ensureStarted()
        val simulated = engine.simulated
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            engine.results.collect { result ->
                emitter.updateView(render(context, result, simulated, config))
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun render(
        context: Context,
        result: WBalResult?,
        simulated: Boolean,
        config: ViewConfig,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.field_wbal)
        val background = if (result == null) warmupColor() else backgroundColor(result)
        // Use a rounded shape drawable tinted to the status color so the fill respects Karoo's
        // rounded field edge (a flat setBackgroundColor rect would bleed past the corners). When
        // the rider disables field boundaries there's no rounded edge, so use sharp corners.
        rv.setInt(
            R.id.wbal_root,
            "setBackgroundResource",
            if (config.boundariesEnabled) R.drawable.bg_field_rounded else R.drawable.bg_field_square,
        )
        rv.setColorStateList(R.id.wbal_root, "setBackgroundTintList", ColorStateList.valueOf(background))

        // White text on the colored background, with the rider's configured horizontal
        // alignment. The value font size is left to the layout's autoSizeTextType so it fills the
        // tile like the built-in numeric fields (setting it here would disable auto-sizing).
        val hGravity = when (config.alignment) {
            ViewConfig.Alignment.LEFT -> Gravity.START
            ViewConfig.Alignment.CENTER -> Gravity.CENTER_HORIZONTAL
            ViewConfig.Alignment.RIGHT -> Gravity.END
        }
        rv.setInt(R.id.wbal_title, "setGravity", hGravity)
        rv.setInt(R.id.wbal_value, "setGravity", hGravity or Gravity.CENTER_VERTICAL)
        rv.setTextViewText(R.id.wbal_title, context.getString(titleResId))
        rv.setTextColor(R.id.wbal_title, Color.WHITE)
        rv.setTextColor(R.id.wbal_value, Color.WHITE)

        val text = if (result == null) {
            if (simulated) "— *" else "—"
        } else {
            val suffix = if (simulated) " *" else ""
            formatValue(result) + suffix
        }
        rv.setTextViewText(R.id.wbal_value, text)
        return rv
    }

    companion object {
        private val PREVIEW = WBalResult(
            wBalJoules = 14_200.0,
            wMaxJoules = 20_000.0,
            pctRemaining = 71.0,
            status = WBalStatus.GOOD,
            rateJoulesPerSec = -85.0,
            secondsToBoundary = 167,
        )
    }
}
