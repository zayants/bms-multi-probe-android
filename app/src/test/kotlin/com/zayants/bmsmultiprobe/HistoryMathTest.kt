package com.zayants.bmsmultiprobe

import com.zayants.bmsmultiprobe.history.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HistoryMathTest {
    @Test fun sixCalendarMonthsHandlesLeapDaysAndUnequalMonths() {
        fun date(value: String) = Instant.parse(value).toEpochMilli()
        assertEquals(date("2024-02-29T12:00:00Z"), HistoryPolicy.cutoff(date("2024-08-31T12:00:00Z")))
        assertEquals(date("2025-02-28T12:00:00Z"), HistoryPolicy.cutoff(date("2025-08-31T12:00:00Z")))
        assertEquals(date("2026-03-07T12:00:00Z"), HistoryPolicy.cutoff(date("2026-09-07T12:00:00Z")))
    }

    @Test fun recordsOnlyFreshConnectedValidSamples() {
        val cells = listOf(3.271f, 3.268f, 3.28f)
        assertTrue(HistoryPolicy.valid(100_000, 105_000, true, cells))
        assertFalse(HistoryPolicy.valid(100_000, 116_000, true, cells))
        assertFalse(HistoryPolicy.valid(100_001, 100_000, true, cells))
        assertFalse(HistoryPolicy.valid(100_000, 100_000, false, cells))
        assertFalse(HistoryPolicy.valid(100_000, 100_000, true, cells + Float.NaN))
        assertFalse(HistoryPolicy.valid(100_000, 100_000, true, List(33) { 3.2f }))
    }

    @Test fun compactEncodingPreservesMillivoltsFor32Cells() {
        val cells = List(32) { 3.200f + it * 0.001f }
        val bytes = HistoryPolicy.encode(cells)
        assertEquals(64, bytes.size)
        cells.zip(HistoryPolicy.decode(bytes)).forEach { (expected, actual) ->
            assertEquals(expected, actual, 0.000001f)
        }
    }

    @Test fun averagingKeepsCountExtremaAndGaps() {
        val a = HistoryPoint(0, 0, 1, listOf(3.0f))
        val b = HistoryPoint(5_000, 10_000, 2, listOf(3.3f), listOf(3.2f), listOf(3.4f))
        val combined = a.combine(b)
        assertEquals(3, combined.count)
        assertEquals(3.2f, combined.mean[0], 0.000001f)
        assertEquals(3f, combined.low[0])
        assertEquals(3.4f, combined.high[0])
        assertFalse(combined.hasGap)
        assertTrue(combined.combine(HistoryPoint(60_000, 60_000, 1, listOf(3.1f))).hasGap)
    }

    @Test fun sixMonthReductionIsBoundedAndPreservesSpikes() {
        val duration = 180 * HistoryPolicy.DAY
        val reducer = HistoryReducer(0, duration)
        for (i in 0 until 259_200) {
            val value = if (i == 123_456) 3.9f else 3.2f
            reducer.add(HistoryPoint(i * 60_000L, i * 60_000L + 55_000, 12, List(32) { value }))
        }
        val points = reducer.points()
        assertTrue(points.size <= 700)
        assertEquals(3.9f, points.maxOf { it.high[0] })
        assertEquals(259_200 * 12, points.sumOf { it.count })
    }

    @Test fun topologyChangesNeverConnectDifferentCellSets() {
        val reducer = HistoryReducer(0, 60_000, 1)
        reducer.add(HistoryPoint(0, 0, 1, List(8) { 3.2f }))
        reducer.add(HistoryPoint(5_000, 5_000, 1, List(16) { 3.3f }))
        assertTrue(reducer.points().single().hasGap)
        assertEquals(16, reducer.points().single().mean.size)
    }

    @Test fun pinchKeepsFocusAndPanClampsAtBothEnds() {
        val view = TimeViewport(0, 10_000_000, 2_000_000, 3_000_000)
        view.zoom(2.0, 0.25)
        assertEquals(500_000, view.span)
        assertEquals(2_250_000, view.start + (view.span * 0.25).toLong())
        view.pan(-100.0)
        assertEquals(0, view.start)
        view.pan(100.0)
        assertEquals(10_000_000, view.end)
        repeat(40) { view.zoom(2.0, 0.5) }
        assertEquals(HistoryPolicy.MIN_SPAN, view.span)
        repeat(40) { view.zoom(0.5, 0.5) }
        assertEquals(10_000_000, view.span)
    }
}
