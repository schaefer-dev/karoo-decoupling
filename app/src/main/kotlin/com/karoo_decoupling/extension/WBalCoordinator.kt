package com.karoo_decoupling.extension

import com.karoo_decoupling.data.WBalSettings
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** Last known W' balance of the open ride, persisted so a mid-ride service restart can resume. */
data class WBalSnapshot(val wBalJoules: Double, val elapsedSec: Double)

/** Persistence seam for [WBalSnapshot] (DataStore in the app, an in-memory fake in tests). */
interface WBalSnapshotStore {
    suspend fun load(): WBalSnapshot?
    suspend fun save(snapshot: WBalSnapshot)
    suspend fun clear()
}

/**
 * Pure-flow coordinator for live W' balance. Wires Power / ELAPSED_TIME / RideState and
 * the rider's [WBalSettings] into a sequence of [WBalResult]? emissions — one per
 * ELAPSED_TIME tick. Free of Android and SDK service types so it is fully JVM-testable.
 *
 * ELAPSED_TIME is the clock: it auto-pauses with the ride, and we re-derive `dt` from the
 * delta between successive elapsed values so a pause naturally yields dt=0 (the calculator
 * also clamps dt to [0,5]). The calculator is reset to W'max only on an Idle->Recording
 * transition actually observed here. Emits `null` until settings are configured (CP > 0).
 *
 * Ride state and elapsed ticks are handled by one sequential collector so a reset can never
 * interleave with an integration step.
 *
 * With a [snapshotStore], the balance is saved every [SAVE_EVERY_SEC] of moving time and
 * restored when the coordinator starts in the middle of a ride (no Idle seen yet), so a
 * service restart doesn't jump the fields back to 100 %. The snapshot is cleared on Idle.
 */
class WBalCoordinator(
    private val powerFlow: Flow<StreamState>,
    private val elapsedFlow: Flow<StreamState>,
    private val rideStateFlow: Flow<RideState>,
    private val settingsFlow: Flow<WBalSettings>,
    private val calc: WBalCalculator = WBalCalculator(),
    private val snapshotStore: WBalSnapshotStore? = null,
) {
    @Volatile private var lastPower: Double? = null
    @Volatile private var settings: WBalSettings = WBalSettings()

    private sealed interface Event {
        data class Ride(val state: RideState) : Event
        data class Tick(val state: StreamState) : Event
    }

    fun run(scope: CoroutineScope): Flow<WBalResult?> {
        val out = Channel<WBalResult?>(Channel.UNLIMITED)

        scope.launch {
            powerFlow.collect { state ->
                lastPower = (state as? StreamState.Streaming)?.dataPoint?.singleValue
            }
        }
        scope.launch {
            settingsFlow.collect { settings = it }
        }
        scope.launch {
            var prevRide: RideState? = null
            var sawIdle = false
            var restoreChecked = false
            var lastElapsedSec: Double? = null
            var lastSavedSec: Double? = null

            merge(
                rideStateFlow.map { Event.Ride(it) },
                elapsedFlow.map { Event.Tick(it) },
            ).collect { event ->
                when (event) {
                    is Event.Ride -> {
                        val rs = event.state
                        if (rs is RideState.Idle) {
                            sawIdle = true
                            // No open ride: any saved balance is stale from here on.
                            if (prevRide !is RideState.Idle) snapshotStore?.clear()
                        }
                        // Fresh ride (Idle -> Recording): start full.
                        if (rs is RideState.Recording && prevRide is RideState.Idle) {
                            calc.reset(settings.wPrimeMax.toDouble())
                            lastElapsedSec = null
                            lastSavedSec = null
                        }
                        prevRide = rs
                    }
                    is Event.Tick -> {
                        val elapsed = (event.state as? StreamState.Streaming)?.dataPoint?.singleValue
                            ?: return@collect
                        val cfg = settings
                        if (!cfg.isConfigured) {
                            out.send(null)
                            lastElapsedSec = elapsed
                            return@collect
                        }
                        if (!restoreChecked) {
                            restoreChecked = true
                            // Started mid-ride (service restart): resume from the saved balance.
                            if (!sawIdle) {
                                snapshotStore?.load()
                                    ?.takeIf { it.elapsedSec <= elapsed }
                                    ?.let { calc.restore(it.wBalJoules) }
                            }
                        }
                        val prev = lastElapsedSec
                        lastElapsedSec = elapsed
                        val dt = if (prev == null) 0.0 else (elapsed - prev)
                        val power = lastPower ?: 0.0
                        val result = calc.step(dt, power, cfg.criticalPower.toDouble(), cfg.wPrimeMax.toDouble())
                        out.send(result)

                        val rideOpen = prevRide != null && prevRide !is RideState.Idle
                        val saved = lastSavedSec
                        if (snapshotStore != null && rideOpen && (saved == null || elapsed - saved >= SAVE_EVERY_SEC)) {
                            snapshotStore.save(WBalSnapshot(result.wBalJoules, elapsed))
                            lastSavedSec = elapsed
                        }
                    }
                }
            }
            out.close()
        }

        return out.consumeAsFlow()
    }

    companion object {
        /** Moving-time interval between persisted snapshots. */
        const val SAVE_EVERY_SEC = 30.0
    }
}
