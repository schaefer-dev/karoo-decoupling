package com.karoo_decoupling.extension

import com.karoo_decoupling.BuildConfig
import com.karoo_decoupling.data.SettingsRepository
import com.karoo_decoupling.data.WBalSettings
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Shared W'bal computation. Runs a single [WBalCoordinator] off the real (or simulated)
 * streams and republishes its output as a hot [StateFlow] so all five W'bal display fields
 * read one computation instead of each spinning up their own coordinator.
 *
 * Simulated vs real selection mirrors [DecouplingDataType] and is gated on BuildConfig.DEBUG.
 * The [simulated] flag is exposed so the fields can append the " *" synthetic-data marker.
 */
class WBalEngine(
    private val karooSystem: KarooSystemService,
    private val settingsRepository: SettingsRepository,
) {
    val simulated: Boolean = BuildConfig.DEBUG

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _results = MutableStateFlow<WBalResult?>(null)
    val results: StateFlow<WBalResult?> = _results

    private var started = false

    @Synchronized
    fun ensureStarted() {
        if (started) return
        started = true

        val powerFlow: Flow<StreamState>
        val elapsedFlow: Flow<StreamState>
        val rideStateFlow: Flow<RideState>
        val settingsFlow: Flow<WBalSettings>

        if (simulated) {
            powerFlow = SimulatedStreams.wbalPower()
            elapsedFlow = SimulatedStreams.elapsedTime()
            rideStateFlow = SimulatedStreams.rideState()
            // Fall back to demo values when the rider hasn't configured anything yet, so the
            // simulator always shows a live sawtooth on-device.
            settingsFlow = settingsRepository.settingsFlow.map { s ->
                if (s.isConfigured) s else WBalSettings(criticalPower = 250, wPrimeMax = WBalSettings.WPRIME_DEFAULT)
            }
        } else {
            powerFlow = karooSystem.streamDataFlow(DataType.Type.POWER)
            elapsedFlow = karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME)
            rideStateFlow = karooSystem.consumerFlow()
            settingsFlow = settingsRepository.settingsFlow
        }

        val coordinator = WBalCoordinator(powerFlow, elapsedFlow, rideStateFlow, settingsFlow)
        scope.launch {
            coordinator.run(this).collect { _results.value = it }
        }
    }
}
