package com.karoo_decoupling.extension

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.widget.RemoteViews
import com.karoo_decoupling.BuildConfig
import com.karoo_decoupling.R
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class DecouplingDataType(
    private val karooSystem: KarooSystemService,
    extension: String,
) : DataTypeImpl(extension, "decoupling") {

    override fun startStream(emitter: Emitter<StreamState>) {
        // Live data field uses startView; emit Unavailable for the raw-value channel.
        emitter.onNext(StreamState.NotAvailable)
        emitter.setCancellable { }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Suppress Karoo's own header bar so our colored background fills the whole tile; we draw
        // a small title inside the layout instead (mirrors the built-in fields and the W'bal fields).
        emitter.onNext(UpdateGraphicConfig(showHeader = false, formatDataTypeId = null))

        if (config.preview) {
            emitter.updateView(render(context, config, DecouplingResult(1.85, 1.79, 3.24)))
            return
        }

        val scope = CoroutineScope(Dispatchers.IO)
        val simulated = BuildConfig.DEBUG

        val hrFlow: Flow<StreamState>
        val powerFlow: Flow<StreamState>
        val rideStateFlow: Flow<RideState>
        val elapsedFlow: Flow<StreamState>

        if (simulated) {
            hrFlow = SimulatedStreams.heartRate()
            powerFlow = SimulatedStreams.power()
            rideStateFlow = SimulatedStreams.rideState()
            elapsedFlow = SimulatedStreams.elapsedTime()
        } else {
            hrFlow = karooSystem.streamDataFlow(DataType.Type.HEART_RATE)
            powerFlow = karooSystem.streamDataFlow(DataType.Type.POWER)
            rideStateFlow = karooSystem.consumerFlow()
            elapsedFlow = karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME)
        }

        val coordinator = DecouplingCoordinator(hrFlow, powerFlow, rideStateFlow, elapsedFlow)
        scope.launch {
            if (simulated) {
                coordinator.run(this).collect { result ->
                    emitter.updateView(render(context, config, result, simulated = true))
                }
            } else {
                // Release: throttle renders to ~30 s to save battery. The coordinator
                // still receives every per-second sample (EF1/EF2 need them); only the
                // RemoteViews IPC + redraw is rate-limited. One extra paint fires on the
                // warm-up→first-value transition so the rider doesn't wait up to 30 s
                // after the 120 s threshold to see the first number.
                var latest: DecouplingResult? = null
                var seenFirstResult = false
                emitter.updateView(render(context, config, null, simulated = false))

                val collector = launch {
                    coordinator.run(this).collect { result ->
                        latest = result
                        if (!seenFirstResult && result != null) {
                            seenFirstResult = true
                            emitter.updateView(render(context, config, result, simulated = false))
                        }
                    }
                }
                launch {
                    while (isActive) {
                        delay(30_000)
                        emitter.updateView(render(context, config, latest, simulated = false))
                    }
                }
                collector.join()
            }
        }

        emitter.setCancellable { scope.cancel() }
    }

    private fun render(
        context: Context,
        config: ViewConfig,
        result: DecouplingResult?,
        simulated: Boolean = false,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.field_decoupling)
        val background = if (result == null) {
            DecouplingColors.WARMUP
        } else {
            DecouplingColors.forDrift(result.driftPct)
        }
        // Use a rounded shape drawable tinted to the drift color so the fill respects Karoo's
        // rounded field edge (a flat setBackgroundColor rect would bleed past the corners). When
        // the rider disables field boundaries there's no rounded edge, so use sharp corners.
        rv.setInt(
            R.id.decoupling_root,
            "setBackgroundResource",
            if (config.boundariesEnabled) R.drawable.bg_field_rounded else R.drawable.bg_field_square,
        )
        rv.setColorStateList(R.id.decoupling_root, "setBackgroundTintList", ColorStateList.valueOf(background))

        // White text on the colored background, with the rider's configured horizontal
        // alignment. The value font size is left to the layout's autoSizeTextType so it fills the
        // tile like the built-in numeric fields (setting it here would disable auto-sizing).
        val hGravity = when (config.alignment) {
            ViewConfig.Alignment.LEFT -> Gravity.START
            ViewConfig.Alignment.CENTER -> Gravity.CENTER_HORIZONTAL
            ViewConfig.Alignment.RIGHT -> Gravity.END
        }
        rv.setInt(R.id.decoupling_title, "setGravity", hGravity)
        rv.setInt(R.id.decoupling_value, "setGravity", hGravity or Gravity.CENTER_VERTICAL)
        rv.setTextViewText(R.id.decoupling_title, context.getString(R.string.decoupling_field_name))
        rv.setTextColor(R.id.decoupling_title, Color.WHITE)
        rv.setTextColor(R.id.decoupling_value, Color.WHITE)

        val text = if (result == null) {
            if (simulated) "— *" else "—"
        } else {
            val suffix = if (simulated) " *" else ""
            String.format(Locale.US, "%+.1f%%%s", result.driftPct, suffix)
        }
        rv.setTextViewText(R.id.decoupling_value, text)
        return rv
    }
}
