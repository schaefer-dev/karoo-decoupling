package com.karoo_decoupling.extension

import android.content.Context
import android.graphics.Color
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
import kotlinx.coroutines.flow.Flow
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
        emitter.onNext(UpdateGraphicConfig(showHeader = true, formatDataTypeId = null))

        if (config.preview) {
            emitter.updateView(render(context, DecouplingResult(1.85, 1.79, 3.24)))
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
            coordinator.run(this).collect { result ->
                emitter.updateView(render(context, result, simulated))
            }
        }

        emitter.setCancellable { scope.cancel() }
    }

    private fun render(
        context: Context,
        result: DecouplingResult?,
        simulated: Boolean = false,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.field_decoupling)
        val background = if (result == null) {
            DecouplingColors.WARMUP
        } else {
            DecouplingColors.forDrift(result.driftPct)
        }
        rv.setInt(R.id.decoupling_root, "setBackgroundColor", background)
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
