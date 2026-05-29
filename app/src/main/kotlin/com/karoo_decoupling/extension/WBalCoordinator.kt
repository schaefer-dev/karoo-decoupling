package com.karoo_decoupling.extension

import com.karoo_decoupling.data.WBalSettings
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

/**
 * Pure-flow coordinator for live W' balance. Wires Power / ELAPSED_TIME / RideState and
 * the rider's [WBalSettings] into a sequence of [WBalResult]? emissions — one per
 * ELAPSED_TIME tick. Free of Android and SDK service types so it is fully JVM-testable.
 *
 * ELAPSED_TIME is the clock: it auto-pauses with the ride, and we re-derive `dt` from the
 * delta between successive elapsed values so a pause naturally yields dt=0 (the calculator
 * also clamps dt to [0,5]). The calculator is reset to W'max on each Idle->Recording
 * transition. Emits `null` until settings are configured (CP > 0).
 */
class WBalCoordinator(
    private val powerFlow: Flow<StreamState>,
    private val elapsedFlow: Flow<StreamState>,
    private val rideStateFlow: Flow<RideState>,
    private val settingsFlow: Flow<WBalSettings>,
    private val calc: WBalCalculator = WBalCalculator(),
) {
    @Volatile private var lastPower: Double? = null
    @Volatile private var settings: WBalSettings = WBalSettings()
    private var lastElapsedSec: Double? = null

    fun run(scope: CoroutineScope): Flow<WBalResult?> {
        val out = Channel<WBalResult?>(Channel.UNLIMITED)

        val jobs = mutableListOf<Job>()
        jobs += scope.launch {
            powerFlow.collect { state ->
                lastPower = (state as? StreamState.Streaming)?.dataPoint?.singleValue
            }
        }
        jobs += scope.launch {
            settingsFlow.collect { settings = it }
        }
        jobs += scope.launch {
            // Reset the balance to full on a fresh ride (Idle -> Recording transition).
            var wasIdle = true
            rideStateFlow.collect { rs ->
                if (rs is RideState.Recording && wasIdle) {
                    calc.reset(settings.wPrimeMax.toDouble())
                    lastElapsedSec = null
                }
                wasIdle = rs is RideState.Idle
            }
        }
        jobs += scope.launch {
            elapsedFlow.collect { state ->
                val elapsed = (state as? StreamState.Streaming)?.dataPoint?.singleValue
                    ?: return@collect
                val cfg = settings
                if (!cfg.isConfigured) {
                    out.send(null)
                    lastElapsedSec = elapsed
                    return@collect
                }
                val prev = lastElapsedSec
                lastElapsedSec = elapsed
                val dt = if (prev == null) 0.0 else (elapsed - prev)
                val power = lastPower ?: 0.0
                val result = calc.step(dt, power, cfg.criticalPower.toDouble(), cfg.wPrimeMax.toDouble())
                out.send(result)
            }
            out.close()
        }

        return out.consumeAsFlow()
    }
}
