package com.karoo_decoupling.extension

import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

/**
 * Pure-flow coordinator. Wires HR / Power / RideState / ELAPSED_TIME streams into a
 * sequence of [DecouplingResult]? emissions — one per ELAPSED_TIME tick. Kept free of
 * Android and SDK service types so it is fully unit-testable on the JVM.
 *
 * The widget renders `null` as "—". Render happens on every tick (per-second cadence)
 * so the user gets a live preview that satisfies the "at least once per minute" goal
 * trivially.
 */
class DecouplingCoordinator(
    private val hrFlow: Flow<StreamState>,
    private val powerFlow: Flow<StreamState>,
    private val rideStateFlow: Flow<RideState>,
    private val elapsedFlow: Flow<StreamState>,
    private val calc: DecouplingCalculator = DecouplingCalculator(),
) {
    @Volatile private var lastHr: Double? = null
    @Volatile private var lastPower: Double? = null

    fun run(scope: CoroutineScope): Flow<DecouplingResult?> {
        val out = Channel<DecouplingResult?>(Channel.UNLIMITED)

        val jobs = mutableListOf<Job>()
        jobs += scope.launch {
            hrFlow.collect { state ->
                lastHr = (state as? StreamState.Streaming)?.dataPoint?.singleValue
            }
        }
        jobs += scope.launch {
            powerFlow.collect { state ->
                lastPower = (state as? StreamState.Streaming)?.dataPoint?.singleValue
            }
        }
        jobs += scope.launch {
            // Reset accumulator on a fresh ride (Idle -> Recording transition).
            var wasIdle = true
            rideStateFlow.collect { rs ->
                if (rs is RideState.Recording && wasIdle) calc.reset()
                wasIdle = rs is RideState.Idle
            }
        }
        jobs += scope.launch {
            // ELAPSED_TIME pauses automatically on RideState.Paused, so it doubles as a
            // moving-time clock.
            elapsedFlow.collect { state ->
                val elapsed = (state as? StreamState.Streaming)?.dataPoint?.singleValue
                    ?: return@collect
                val movingSec = elapsed.toInt()
                val hr = lastHr
                val power = lastPower
                if (hr != null && power != null) {
                    calc.addSample(movingSec, power, hr)
                }
                out.send(calc.result())
            }
            out.close()
        }

        return out.consumeAsFlow()
    }
}
