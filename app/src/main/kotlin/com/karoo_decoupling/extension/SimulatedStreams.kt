package com.karoo_decoupling.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Synthetic stream generators for the debug build variant. Lets you validate the widget
 * on a real Karoo (rendering, lifecycle, profile integration) without going on a ride.
 *
 * Curve:
 *   - 0..240s   : 200 W, 140 bpm (flat warmup — expected drift ~ 0%)
 *   - 240..480s : 200 W, HR ramps linearly 140 -> 170 (cardiac drift — expected ~ +10-12%)
 *   - >480s     : holds final values
 *
 * The widget shows a trailing " *" while simulated streams are active (see
 * DecouplingDataType.render) so it is obvious you are not looking at real sensor data.
 */
object SimulatedStreams {
    private const val TICK_MS = 100L

    fun elapsedTime(): Flow<StreamState> = flow {
        var t = 0
        while (true) {
            emit(streaming(DataType.Type.ELAPSED_TIME, t.toDouble()))
            delay(TICK_MS)
            t++
        }
    }

    fun heartRate(): Flow<StreamState> = flow {
        var t = 0
        while (true) {
            val hr = when {
                t < 240 -> 140.0
                t < 480 -> 140.0 + (t - 240) * (30.0 / 240.0)
                else -> 170.0
            }
            emit(streaming(DataType.Type.HEART_RATE, hr))
            delay(TICK_MS)
            t++
        }
    }

    fun power(): Flow<StreamState> = flow {
        while (true) {
            emit(streaming(DataType.Type.POWER, 200.0))
            delay(TICK_MS)
        }
    }

    /**
     * Power profile for exercising the W'bal fields without a ride: repeating 60 s surges
     * above CP (350 W) followed by 60 s of easy soft-pedalling below CP (120 W). With a
     * typical CP ~250 W this drives a visible deplete -> recover sawtooth so you can watch
     * the percent fall, the status step down through the bands, then recover.
     */
    fun wbalPower(): Flow<StreamState> = flow {
        var t = 0
        while (true) {
            val watts = if ((t % 120) < 60) 350.0 else 120.0
            emit(streaming(DataType.Type.POWER, watts))
            delay(TICK_MS)
            t++
        }
    }

    fun rideState(): Flow<RideState> = flow {
        emit(RideState.Idle)
        delay(100L)
        emit(RideState.Recording)
        // Hold forever — no further transitions for the simulator.
        delay(Long.MAX_VALUE)
    }

    private fun streaming(typeId: String, value: Double): StreamState =
        StreamState.Streaming(
            DataPoint(typeId, values = mapOf(DataType.Field.SINGLE to value)),
        )
}
