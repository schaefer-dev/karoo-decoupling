package com.karoo_decoupling.extension

import com.karoo_decoupling.data.WBalSettings
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WBalCoordinatorTest {

    private val cp = 250
    private val wMax = 20_000

    private fun streaming(typeId: String, value: Double): StreamState =
        StreamState.Streaming(DataPoint(typeId, values = mapOf(DataType.Field.SINGLE to value)))

    private fun elapsedFlowOf(range: IntRange): List<StreamState> =
        range.map { streaming(DataType.Type.ELAPSED_TIME, it.toDouble()) }

    private suspend fun TestScope.runScenario(
        power: List<StreamState>,
        ride: List<RideState>,
        elapsed: List<StreamState>,
        settings: WBalSettings,
    ): List<WBalResult?> {
        val coordinator = WBalCoordinator(
            powerFlow = power.asReplayFlow(),
            elapsedFlow = elapsed.asReplayFlow(),
            rideStateFlow = ride.asReplayFlow(),
            settingsFlow = flowOf(settings),
        )
        val emissions = coordinator.run(this).take(elapsed.size).toList()
        coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        return emissions
    }

    @Test
    fun `emits null until settings are configured`() = runTest {
        val emissions = runScenario(
            power = listOf(streaming(DataType.Type.POWER, 300.0)),
            ride = listOf(RideState.Recording),
            elapsed = elapsedFlowOf(0..10),
            settings = WBalSettings(criticalPower = 0), // unconfigured
        )
        assertTrue("all emissions null when CP unconfigured", emissions.all { it == null })
    }

    @Test
    fun `starts at full balance and depletes above CP`() = runTest {
        val emissions = runScenario(
            power = listOf(streaming(DataType.Type.POWER, (cp + 100).toDouble())),
            ride = listOf(RideState.Recording),
            elapsed = elapsedFlowOf(0..60),
            settings = WBalSettings(cp, wMax),
        )
        // First tick: dt=0 (no previous elapsed) -> still full.
        assertEquals(wMax.toDouble(), emissions[0]!!.wBalJoules, 1e-9)
        // After 60 s above CP, balance must have dropped and status moved off FRESH eventually.
        val last = emissions.last()!!
        assertTrue("should deplete, was ${last.wBalJoules}", last.wBalJoules < wMax)
        assertTrue(last.rateJoulesPerSec < 0.0)
    }

    @Test
    fun `recovers below CP`() = runTest {
        // Hard for 60 s, then easy for 120 s — final balance should exceed the trough.
        // Power and elapsed must advance together, so interleave them on hot flows rather
        // than replaying (a replay flow would latch the *last* power before any tick runs).
        val power = MutableSharedFlow<StreamState>(replay = 1, extraBufferCapacity = 8)
        val elapsed = MutableSharedFlow<StreamState>(extraBufferCapacity = 1024)
        val coordinator = WBalCoordinator(
            powerFlow = power,
            elapsedFlow = elapsed,
            rideStateFlow = flowOf(RideState.Recording),
            settingsFlow = flowOf(WBalSettings(cp, wMax)),
        )
        val out = mutableListOf<WBalResult?>()
        val job = launch { coordinator.run(this).collect { out += it } }
        testScheduler.runCurrent()

        power.emit(streaming(DataType.Type.POWER, (cp + 200).toDouble()))
        for (s in 0..60) {
            elapsed.emit(streaming(DataType.Type.ELAPSED_TIME, s.toDouble()))
            testScheduler.runCurrent()
        }
        val trough = out.last()!!.wBalJoules

        power.emit(streaming(DataType.Type.POWER, 0.0))
        testScheduler.runCurrent()
        for (s in 61..180) {
            elapsed.emit(streaming(DataType.Type.ELAPSED_TIME, s.toDouble()))
            testScheduler.runCurrent()
        }
        val end = out.last()!!.wBalJoules
        assertTrue("should recover after the surge: trough=$trough end=$end", end > trough)

        job.cancel()
    }

    @Test
    fun `ELAPSED_TIME milliseconds yield physically correct depletion (regression)`() = runTest {
        // The real SDK emits ELAPSED_TIME in MILLISECONDS. Feeding ms straight into the
        // coordinator made dt ~1000/tick -> clamped to 5 s -> ~5x too-fast depletion. With the
        // boundary .elapsedSeconds() conversion, dt is a real 1 s/tick and depletion matches the
        // Skiba model. 30 s at 100 W over CP must drop W' only the small physical amount, NOT to
        // the EMPTY band the bug produced.
        val powerW = (cp + 100).toDouble() // 100 W over CP
        val elapsedMs = (0..30).map { streaming(DataType.Type.ELAPSED_TIME, (it * 1000).toDouble()) }
        val coordinator = WBalCoordinator(
            powerFlow = listOf(streaming(DataType.Type.POWER, powerW)).asReplayFlow(),
            elapsedFlow = elapsedMs.asReplayFlow().elapsedSeconds(),
            rideStateFlow = listOf(RideState.Recording).asReplayFlow(),
            settingsFlow = flowOf(WBalSettings(cp, wMax)),
        )
        val emissions = coordinator.run(this).take(elapsedMs.size).toList()
        coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }

        // Deterministic reference: 30 one-second Skiba steps (the model in WBalCalculator).
        var ref = wMax.toDouble()
        repeat(30) {
            val recovery = (wMax - ref) / WBalCalculator.TAU
            val rate = recovery - (powerW - cp) // power > cp
            ref = (ref + rate * 1.0).coerceIn(0.0, wMax.toDouble())
        }
        assertEquals(ref, emissions.last()!!.wBalJoules, 1e-6)

        // Bug tripwire: correct end ~17k (>85%); the old 5x-clamped behavior craters to ~5k (EMPTY).
        assertTrue("W' must not over-deplete, was ${emissions.last()!!.wBalJoules}", emissions.last()!!.wBalJoules > 15_000.0)
        assertTrue("pct must stay high, was ${emissions.last()!!.pctRemaining}", emissions.last()!!.pctRemaining > 70.0)
    }

    @Test
    fun `pause produces no depletion`() = runTest {
        // Two segments at the same elapsed value (clock stalled) -> dt=0 -> no change.
        val elapsed = elapsedFlowOf(0..30) + List(20) { streaming(DataType.Type.ELAPSED_TIME, 30.0) }
        val emissions = runScenario(
            power = listOf(streaming(DataType.Type.POWER, (cp + 100).toDouble())),
            ride = listOf(RideState.Recording),
            elapsed = elapsed,
            settings = WBalSettings(cp, wMax),
        )
        val atStall = emissions[30]!!.wBalJoules
        val afterStall = emissions.last()!!.wBalJoules
        // Duplicate-second elapsed -> dt=0 each time -> balance frozen during the stall.
        assertEquals(atStall, afterStall, 1e-9)
    }

    @Test
    fun `Idle to Recording resets balance to full`() = runTest {
        val ride = MutableSharedFlow<RideState>(replay = 1, extraBufferCapacity = 8)
        val elapsed = MutableSharedFlow<StreamState>(extraBufferCapacity = 1024)
        val power = flowOf(streaming(DataType.Type.POWER, (cp + 300).toDouble()))
        val settings = flowOf(WBalSettings(cp, wMax))

        ride.tryEmit(RideState.Idle)
        val coordinator = WBalCoordinator(power, elapsed, ride, settings)
        val out = mutableListOf<WBalResult?>()
        val job = launch { coordinator.run(this).collect { out += it } }
        testScheduler.runCurrent()

        ride.emit(RideState.Recording)
        testScheduler.runCurrent()
        for (s in elapsedFlowOf(0..120)) elapsed.emit(s)
        testScheduler.runCurrent()
        val depleted = out.last()!!.wBalJoules
        assertTrue("first ride should deplete, was $depleted", depleted < wMax)

        // Restart the ride.
        ride.emit(RideState.Idle)
        ride.emit(RideState.Recording)
        testScheduler.runCurrent()
        elapsed.emit(streaming(DataType.Type.ELAPSED_TIME, 0.0))
        testScheduler.runCurrent()
        assertEquals("balance must reset to full on restart", wMax.toDouble(), out.last()!!.wBalJoules, 1e-9)

        job.cancel()
    }

    @Test
    fun `live CP update changes depletion behavior`() = runTest {
        // Power constant at 300 W. With CP=250 it depletes; raise CP to 350 mid-ride and it
        // should start recovering.
        val settings = MutableSharedFlow<WBalSettings>(replay = 1, extraBufferCapacity = 4)
        val elapsed = MutableSharedFlow<StreamState>(extraBufferCapacity = 1024)
        val power = flowOf(streaming(DataType.Type.POWER, 300.0))

        settings.tryEmit(WBalSettings(criticalPower = 250, wPrimeMax = wMax))
        val coordinator = WBalCoordinator(power, elapsed, flowOf(RideState.Recording), settings)
        val out = mutableListOf<WBalResult?>()
        val job = launch { coordinator.run(this).collect { out += it } }
        testScheduler.runCurrent()

        for (s in elapsedFlowOf(0..60)) elapsed.emit(s)
        testScheduler.runCurrent()
        val trough = out.last()!!.wBalJoules
        assertTrue("depletes with CP<power", trough < wMax)

        // Now CP rises above the power output.
        settings.emit(WBalSettings(criticalPower = 350, wPrimeMax = wMax))
        testScheduler.runCurrent()
        for (s in (61..180).map { streaming(DataType.Type.ELAPSED_TIME, it.toDouble()) }) elapsed.emit(s)
        testScheduler.runCurrent()
        val end = out.last()!!
        assertTrue("recovers once CP>power: trough=$trough end=${end.wBalJoules}", end.wBalJoules > trough)
        assertTrue(end.rateJoulesPerSec > 0.0)

        job.cancel()
    }
}

/** Replay-all flow that immediately emits each element, used to feed deterministic inputs. */
private fun <T> List<T>.asReplayFlow(): kotlinx.coroutines.flow.Flow<T> =
    kotlinx.coroutines.flow.flow { for (item in this@asReplayFlow) emit(item) }
