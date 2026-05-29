package com.karoo_decoupling.extension

import android.content.Context
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Visualizes how the whole-ride drift% (the value shown by [DecouplingDataType]) has
 * trended over time: a 60-min sparkline with the trailing-20-min delta + arrow on top.
 *
 * Thin SDK/Canvas wiring layer — all testable logic lives in [DecouplingTrendBuffer].
 * Runs its own [DecouplingCoordinator] instance so it never shares mutable state with
 * the existing decoupling field.
 */
class DecouplingTrendDataType(
    private val karooSystem: KarooSystemService,
    extension: String,
) : DataTypeImpl(extension, "decoupling_trend") {

    private companion object {
        const val WINDOW_SEC = 3600
        const val LOOKBACK_SEC = 1200
        const val THROTTLE_MS = 30_000L
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        emitter.onNext(StreamState.NotAvailable)
        emitter.setCancellable { }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = true, formatDataTypeId = null))

        if (config.preview) {
            emitter.updateView(render(context, config, previewPoints(), previewDelta()))
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

        val buffer = DecouplingTrendBuffer(windowSec = WINDOW_SEC)
        val coordinator = DecouplingCoordinator(hrFlow, powerFlow, rideStateFlow, elapsedFlow)

        val movingSec = AtomicInteger(0)
        val wasIdle = AtomicBoolean(true)

        scope.launch {
            // Track moving time independently of the coordinator, which consumes
            // ELAPSED_TIME internally and doesn't surface it. ELAPSED_TIME auto-pauses,
            // so this is moving seconds. Reset the buffer on Idle->Recording.
            launch {
                elapsedFlow.collect { state ->
                    val elapsed = (state as? StreamState.Streaming)?.dataPoint?.singleValue
                    if (elapsed != null) movingSec.set(elapsed.toInt())
                }
            }
            launch {
                rideStateFlow.collect { rs ->
                    if (rs is RideState.Recording && wasIdle.get()) buffer.reset()
                    wasIdle.set(rs is RideState.Idle)
                }
            }

            val marker = if (simulated) " *" else ""

            emitter.updateView(render(context, config, buffer.snapshot(), buffer.deltaOver(LOOKBACK_SEC), marker))

            val collector = launch {
                coordinator.run(this).collect { result ->
                    if (result != null) buffer.add(movingSec.get(), result.driftPct)
                }
            }
            launch {
                while (isActive) {
                    delay(THROTTLE_MS)
                    emitter.updateView(
                        render(context, config, buffer.snapshot(), buffer.deltaOver(LOOKBACK_SEC), marker),
                    )
                }
            }
            collector.join()
        }

        emitter.setCancellable { scope.cancel() }
    }

    private fun render(
        context: Context,
        config: ViewConfig,
        points: List<DecouplingTrendBuffer.Point>,
        deltaPct: Double?,
        marker: String = "",
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.field_decoupling_trend)
        val bitmap = DecouplingTrendRenderer.render(
            points = points,
            deltaPct = deltaPct,
            windowSec = WINDOW_SEC,
            widthPx = config.viewSize.first.coerceAtLeast(1),
            heightPx = config.viewSize.second.coerceAtLeast(1),
            marker = marker,
        )
        rv.setImageViewBitmap(R.id.trend_sparkline, bitmap)
        return rv
    }

    private fun previewPoints(): List<DecouplingTrendBuffer.Point> {
        // Gently rising synthetic trend so the field picker shows something representative.
        val pts = ArrayList<DecouplingTrendBuffer.Point>()
        var s = 0
        var d = 1.0
        while (s <= WINDOW_SEC) {
            pts.add(DecouplingTrendBuffer.Point(s, d))
            s += 60
            d += 0.05
        }
        return pts
    }

    private fun previewDelta(): Double = 1.2
}
