package com.karoo_decoupling.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The Karoo SDK emits ELAPSED_TIME in *milliseconds* (the SDK sample divides by 1000 to get
 * seconds). [elapsedSeconds] is the single boundary that converts ms -> s so everything
 * downstream can keep its "seconds" contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KarooExtensionsTest {

    private fun streaming(value: Double): StreamState =
        StreamState.Streaming(
            DataPoint(DataType.Type.ELAPSED_TIME, values = mapOf(DataType.Field.SINGLE to value)),
        )

    @Test
    fun `converts milliseconds to seconds`() = runTest {
        val out = flowOf(streaming(0.0), streaming(1000.0), streaming(30000.0))
            .elapsedSeconds()
            .toList()
        assertEquals(0.0, (out[0] as StreamState.Streaming).dataPoint.singleValue!!, 1e-9)
        assertEquals(1.0, (out[1] as StreamState.Streaming).dataPoint.singleValue!!, 1e-9)
        assertEquals(30.0, (out[2] as StreamState.Streaming).dataPoint.singleValue!!, 1e-9)
    }

    @Test
    fun `preserves dataTypeId and sourceId`() = runTest {
        val point = DataPoint(
            DataType.Type.ELAPSED_TIME,
            values = mapOf(DataType.Field.SINGLE to 5000.0),
            sourceId = "src-1",
        )
        val out = flowOf<StreamState>(StreamState.Streaming(point)).elapsedSeconds().toList()
        val dp = (out.single() as StreamState.Streaming).dataPoint
        assertEquals(DataType.Type.ELAPSED_TIME, dp.dataTypeId)
        assertEquals("src-1", dp.sourceId)
        assertEquals(5.0, dp.singleValue!!, 1e-9)
    }

    @Test
    fun `passes non-streaming states through untouched`() = runTest {
        val idle = StreamState.NotAvailable
        val out = flowOf<StreamState>(idle).elapsedSeconds().toList()
        assertSame("non-streaming state must pass through unchanged", idle, out.single())
    }
}
