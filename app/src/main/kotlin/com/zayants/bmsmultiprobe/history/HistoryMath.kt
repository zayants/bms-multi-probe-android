package com.zayants.bmsmultiprobe.history

import com.zayants.bmsmultiprobe.SampleWindow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

object HistoryPolicy {
    const val DAY = 86_400_000L
    const val MINUTE = 60_000L
    const val ARCHIVE_BUCKET = 5 * MINUTE
    const val MIN_SPAN = MINUTE
    const val MAX_POINTS = 700
    const val GAP = SampleWindow.FRESHNESS_MS

    fun cutoff(now: Long): Long = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = now
        add(Calendar.MONTH, -6)
    }.timeInMillis

    fun valid(timestamp: Long, now: Long, connected: Boolean, cells: List<Float>): Boolean =
        connected && timestamp <= now && now - timestamp <= GAP &&
            cells.size in 3..32 && cells.all { it.isFinite() && it in 1f..5.5f }

    fun encode(values: List<Float>): ByteArray = ByteBuffer.allocate(values.size * 2)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putShort((it * 1000).roundToInt().toShort()) }
        }.array()

    fun decode(bytes: ByteArray): List<Float> {
        require(bytes.size % 2 == 0 && bytes.size in 6..64)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return List(bytes.size / 2) { (buffer.short.toInt() and 0xffff) / 1000f }
    }
}

data class HistoryPoint(
    val first: Long,
    val last: Long,
    val count: Int,
    val mean: List<Float>,
    val low: List<Float> = mean,
    val high: List<Float> = mean,
    val hasGap: Boolean = false,
) {
    val time: Long get() = first + (last - first) / 2

    fun combine(other: HistoryPoint): HistoryPoint {
        require(mean.size == other.mean.size)
        val total = count + other.count
        return HistoryPoint(
            minOf(first, other.first), maxOf(last, other.last), total,
            mean.indices.map { (mean[it] * count.toDouble() + other.mean[it] * other.count) .div(total).toFloat() },
            mean.indices.map { minOf(low[it], other.low[it]) },
            mean.indices.map { maxOf(high[it], other.high[it]) },
            hasGap || other.hasGap || other.first - last > HistoryPolicy.GAP,
        )
    }
}

/** Streaming screen reduction: memory stays bounded even for half a year of 32-cell records.
 * Extrema survive reduction; a bucket with missing samples never bridges adjacent curves. */
class HistoryReducer(private val start: Long, end: Long, private val maximum: Int = HistoryPolicy.MAX_POINTS) {
    private val width = ((end - start).coerceAtLeast(1) + maximum - 1) / maximum
    private val buckets = linkedMapOf<Long, HistoryPoint>()
    fun add(point: HistoryPoint) {
        val key = ((point.time - start) / width).coerceIn(0, (maximum - 1).toLong())
        val previous = buckets[key]
        buckets[key] = if (previous == null) point else if (previous.mean.size == point.mean.size) {
            previous.combine(point)
        } else {
            // A changed cell count is a different topology. Do not fabricate a connecting curve.
            point.copy(hasGap = true)
        }
    }
    fun points(): List<HistoryPoint> = buckets.values.toList()
}

class TimeViewport(var lower: Long, var upper: Long, start: Long, end: Long) {
    var start: Long = start; private set
    var end: Long = end; private set
    val span: Long get() = end - start
    init { set(start, end) }
    fun set(from: Long, to: Long) {
        val duration = (to - from).coerceIn(HistoryPolicy.MIN_SPAN, (upper - lower).coerceAtLeast(HistoryPolicy.MIN_SPAN))
        end = to.coerceIn(lower + duration, upper)
        start = end - duration
    }
    fun zoom(factor: Double, anchor: Double) {
        if (!factor.isFinite() || factor <= 0) return
        val fraction = anchor.coerceIn(0.0, 1.0)
        val duration = (span / factor).toLong().coerceIn(HistoryPolicy.MIN_SPAN, upper - lower)
        val focus = start + span * fraction
        set((focus - duration * fraction).toLong(), (focus + duration * (1 - fraction)).toLong())
    }
    fun pan(fraction: Double) {
        val shift = (span * fraction).toLong()
        set(start + shift, end + shift)
    }
}
