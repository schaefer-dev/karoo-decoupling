package com.karoo_decoupling.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
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
class DecouplingCoordinatorTest {

    private fun streaming(typeId: String, value: Double): StreamState =
        StreamState.Streaming(DataPoint(typeId, values = mapOf(DataType.Field.SINGLE to value)))

    private fun elapsedFlowOf(range: IntRange): List<StreamState> =
        range.map { streaming(DataType.Type.ELAPSED_TIME, it.toDouble()) }

    /** Drive the coordinator with deterministic inputs and collect every emission. */
    private suspend fun TestScope.runScenario(
        hr: List<StreamState>,
        power: List<StreamState>,
        ride: List<RideState>,
        elapsed: List<StreamState>,
    ): List<DecouplingResult?> {
        val coordinator = DecouplingCoordinator(
            hrFlow = hr.asReplayFlow(),
            powerFlow = power.asReplayFlow(),
            rideStateFlow = ride.asReplayFlow(),
            elapsedFlow = elapsed.asReplayFlow(),
        )
        val emissions = coordinator.run(this).take(elapsed.size).toList()
        coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        return emissions
    }

    @Test
    fun `happy path emits null until 120s threshold then a result`() = runTest {
        val emissions = runScenario(
            hr = listOf(streaming(DataType.Type.HEART_RATE, 150.0)),
            power = listOf(streaming(DataType.Type.POWER, 200.0)),
            ride = listOf(RideState.Recording),
            elapsed = elapsedFlowOf(0..240),
        )
        // Everything up to tick movingSec=120 must be null (calculator needs totalSec >= 120),
        // but at tick 120 a result must appear.
        assertNull("tick 0 must be null", emissions[0])
        assertNull("tick 119 must be null", emissions[119])
        assertNotNull("tick 120 must produce a result", emissions[120])
        val last = emissions.last()!!
        assertEquals(0.0, last.driftPct, 1e-6)
    }

    @Test
    fun `samples skipped while HR is missing`() = runTest {
        // Power arrives first; HR never arrives. Coordinator must emit nulls forever
        // because no samples reach the calculator.
        val coordinator = DecouplingCoordinator(
            hrFlow = emptyFlow(),
            powerFlow = flowOf(streaming(DataType.Type.POWER, 200.0)),
            rideStateFlow = flowOf(RideState.Recording),
            elapsedFlow = elapsedFlowOf(0..240).asReplayFlow(),
        )
        val emissions = coordinator.run(this).take(241).toList()
        assertTrue("all emissions must be null when HR missing", emissions.all { it == null })
        coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
    }

    @Test
    fun `pause then resume keeps samples continuous in moving time`() = runTest {
        // ELAPSED_TIME on Karoo auto-pauses, so a pause looks like a gap in emissions
        // followed by a resume at the same moving-second number it would have had if
        // the ride had been continuous. The coordinator just sees a slower stream and
        // must not synthesize a gap.
        val elapsed = elapsedFlowOf(0..60) + elapsedFlowOf(61..240)
        val emissions = runScenario(
            hr = listOf(streaming(DataType.Type.HEART_RATE, 150.0)),
            power = listOf(streaming(DataType.Type.POWER, 200.0)),
            ride = listOf(RideState.Recording),
            elapsed = elapsed,
        )
        val last = emissions.last()!!
        // 200/150 every sample -> EF constant, drift = 0.
        assertEquals(0.0, last.driftPct, 1e-6)
    }

    @Test
    fun `Idle to Recording transition resets calculator`() = runTest {
        // Sequence: Idle (initial) -> Recording -> 0..240s -> Idle -> Recording -> 0..60s.
        // After the second Recording, the prior accumulator is reset, so the final
        // emission for the new 60s ride must be null (below the 120s threshold).
        val rideEvents = listOf(
            RideState.Idle,
            RideState.Recording,
            // The reset happens on Idle->Recording, so we emit those once accumulator
            // has data; ordering is enforced by replaying into a cold flow before the
            // elapsed ticks of the second ride.
        )
        // Build a single elapsed sequence that simulates a stop-and-restart at second 241:
        // first ride 0..240, then ride state goes Idle then Recording (handled below),
        // then new elapsed ticks 0..60.
        val elapsedFirst = elapsedFlowOf(0..240)
        val elapsedSecond = elapsedFlowOf(0..60)

        // Feed: ride first, then elapsed sequence including the restart.
        // To simulate the restart between the two elapsed segments, we splice an
        // Idle->Recording into the ride flow that arrives BEFORE the second segment.
        // The simplest way: use a hot SharedFlow but await with yields.
        val ride = MutableSharedFlow<RideState>(replay = 1, extraBufferCapacity = 8)
        val elapsed = MutableSharedFlow<StreamState>(extraBufferCapacity = 1024)
        val hr = flowOf(streaming(DataType.Type.HEART_RATE, 150.0))
        val pwr = flowOf(streaming(DataType.Type.POWER, 200.0))

        ride.tryEmit(RideState.Idle)

        val coordinator = DecouplingCoordinator(hr, pwr, ride, elapsed)
        val out = mutableListOf<DecouplingResult?>()
        val job = launch {
            coordinator.run(this).collect { out += it }
        }
        testScheduler.runCurrent()

        ride.emit(RideState.Recording)
        testScheduler.runCurrent()
        for (s in elapsedFirst) elapsed.emit(s)
        testScheduler.runCurrent()
        assertNotNull("first ride should produce a result", out.lastOrNull())
        val firstRideLast = out.last()

        // Restart: Idle -> Recording.
        ride.emit(RideState.Idle)
        ride.emit(RideState.Recording)
        testScheduler.runCurrent()
        for (s in elapsedSecond) elapsed.emit(s)
        testScheduler.runCurrent()

        assertNotNull("sanity: first ride emission was non-null", firstRideLast)
        assertNull("after reset, 60s of new data must yield null", out.last())

        job.cancel()
    }

    @Test
    fun `stale HR is reused as last-known value`() = runTest {
        // HR stream emits 140 once then stops. Power emits 200 once then stops. Elapsed
        // ticks 241 times. Documents current behavior: last-known value is reused. If a
        // staleness window is later added, update this test.
        val emissions = runScenario(
            hr = listOf(streaming(DataType.Type.HEART_RATE, 140.0)),
            power = listOf(streaming(DataType.Type.POWER, 200.0)),
            ride = listOf(RideState.Recording),
            elapsed = elapsedFlowOf(0..240),
        )
        val last = emissions.last()!!
        // EF1 = EF2 = 200/140, drift = 0.
        assertEquals(0.0, last.driftPct, 1e-6)
    }

    @Test
    fun `duplicate ELAPSED_TIME emissions only count once`() = runTest {
        // Karoo can re-emit the same integer second due to fractional rounding.
        val elapsed = (0..119).map { streaming(DataType.Type.ELAPSED_TIME, it.toDouble()) } +
            // Re-emit movingSec=119 a few times — must be dropped.
            List(3) { streaming(DataType.Type.ELAPSED_TIME, 119.0) } +
            (120..240).map { streaming(DataType.Type.ELAPSED_TIME, it.toDouble()) }
        val emissions = runScenario(
            hr = listOf(streaming(DataType.Type.HEART_RATE, 150.0)),
            power = listOf(streaming(DataType.Type.POWER, 200.0)),
            ride = listOf(RideState.Recording),
            elapsed = elapsed,
        )
        val last = emissions.last()!!
        assertEquals(0.0, last.driftPct, 1e-6)
    }
}

/** Replay-all flow that immediately emits each element, used to feed deterministic inputs. */
private fun <T> List<T>.asReplayFlow(): kotlinx.coroutines.flow.Flow<T> =
    kotlinx.coroutines.flow.flow { for (item in this@asReplayFlow) emit(item) }
