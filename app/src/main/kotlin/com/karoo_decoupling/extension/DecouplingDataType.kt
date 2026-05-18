package com.karoo_decoupling.extension

import android.content.Context
import android.widget.RemoteViews
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
import kotlinx.coroutines.launch
import java.util.Locale

class DecouplingDataType(
    private val karooSystem: KarooSystemService,
    extension: String,
) : DataTypeImpl(extension, "decoupling") {

    @Volatile private var lastHr: Double? = null
    @Volatile private var lastPower: Double? = null

    override fun startStream(emitter: Emitter<StreamState>) {
        // Live data field uses startView; emit Unavailable for the raw-value channel.
        emitter.onNext(StreamState.NotAvailable)
        emitter.setCancellable { }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = true, formatDataTypeId = null))

        if (config.preview) {
            emitter.updateView(render(context, DecouplingResult(1.85, 1.79, 3.24)))
            return
        }

        val scope = CoroutineScope(Dispatchers.IO)
        val calc = DecouplingCalculator()

        scope.launch {
            karooSystem.streamDataFlow(DataType.Type.HEART_RATE).collect { state ->
                lastHr = (state as? StreamState.Streaming)?.dataPoint?.singleValue
            }
        }
        scope.launch {
            karooSystem.streamDataFlow(DataType.Type.POWER).collect { state ->
                lastPower = (state as? StreamState.Streaming)?.dataPoint?.singleValue
            }
        }
        scope.launch {
            // Reset accumulator when a fresh ride starts (Idle -> Recording transition).
            var wasIdle = true
            karooSystem.consumerFlow<RideState>().collect { rs ->
                if (rs is RideState.Recording && wasIdle) calc.reset()
                wasIdle = rs is RideState.Idle
            }
        }
        scope.launch {
            // ELAPSED_TIME pauses automatically on RideState.Paused, so it doubles as a
            // moving-time clock. Each emission appends a sample (if HR+power are present)
            // and re-renders.
            karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME).collect { state ->
                val elapsed = (state as? StreamState.Streaming)?.dataPoint?.singleValue ?: return@collect
                val movingSec = elapsed.toInt()
                val hr = lastHr
                val power = lastPower
                if (hr != null && power != null) {
                    calc.addSample(movingSec, power, hr)
                }
                emitter.updateView(render(context, calc.result()))
            }
        }

        emitter.setCancellable { scope.cancel() }
    }

    private fun render(context: Context, result: DecouplingResult?): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.field_decoupling)
        if (result == null) {
            rv.setTextViewText(R.id.decoupling_value, "—")
            rv.setTextViewText(R.id.decoupling_ef_first, "EF1 —")
            rv.setTextViewText(R.id.decoupling_ef_second, "EF2 —")
        } else {
            rv.setTextViewText(
                R.id.decoupling_value,
                String.format(Locale.US, "%+.1f%%", result.driftPct),
            )
            rv.setTextViewText(
                R.id.decoupling_ef_first,
                String.format(Locale.US, "EF1 %.2f", result.efFirst),
            )
            rv.setTextViewText(
                R.id.decoupling_ef_second,
                String.format(Locale.US, "EF2 %.2f", result.efSecond),
            )
        }
        return rv
    }
}
