package com.zayants.bmsmultiprobe.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.zayants.bmsmultiprobe.model.ProbeWindow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** App-private, device-address keyed history. No IO is performed on the BLE/main thread.
 * Raw snapshots: 24 h. Five-minute means and extrema: six calendar months.
 * A bounded writer queue prevents disk problems from blocking BLE or growing memory. */
class HistoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "cell-history.db", null, 1) {
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(16), { job -> Thread(job, "bms-history-writer") })
    @Volatile var recordingFailed = false; private set
    private var lastCleanup = 0L

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE raw (device TEXT NOT NULL, t INTEGER NOT NULL, cells BLOB NOT NULL, PRIMARY KEY(device,t))")
        db.execSQL("CREATE INDEX raw_age ON raw(t)")
        db.execSQL("""CREATE TABLE minute (device TEXT NOT NULL, t INTEGER NOT NULL, n INTEGER NOT NULL,
            first INTEGER NOT NULL, last INTEGER NOT NULL, count INTEGER NOT NULL, mean BLOB NOT NULL,
            low BLOB NOT NULL, high BLOB NOT NULL, gap INTEGER NOT NULL, PRIMARY KEY(device,t,n))""")
        db.execSQL("CREATE INDEX minute_age ON minute(t)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future versions must explicitly migrate; never erase accumulated history on upgrade.
        error("History migration required: $oldVersion -> $newVersion")
    }

    fun record(window: ProbeWindow) {
        try {
            writer.execute {
                runCatching {
                    val db = writableDatabase
                    db.beginTransaction()
                    try {
                        window.sessions.forEach { state ->
                            val sample = state.telemetry ?: return@forEach
                            if (!HistoryPolicy.valid(sample.timestamp, window.sampledAt,
                                    state.transportConnected, sample.cellsV)) return@forEach
                            val raw = ContentValues().apply {
                                put("device", state.device.address); put("t", sample.timestamp)
                                put("cells", HistoryPolicy.encode(sample.cellsV))
                            }
                            // The same last BLE packet can appear in several five-second windows.
                            if (db.insertWithOnConflict("raw", null, raw, SQLiteDatabase.CONFLICT_IGNORE) == -1L) return@forEach
                            val bucket = sample.timestamp / HistoryPolicy.ARCHIVE_BUCKET *
                                HistoryPolicy.ARCHIVE_BUCKET
                            val args = arrayOf(state.device.address, bucket.toString(), sample.cellsV.size.toString())
                            val previous = db.rawQuery("SELECT first,last,count,mean,low,high,gap FROM minute WHERE device=? AND t=? AND n=?", args)
                                .use { if (it.moveToFirst()) minutePoint(it) else null }
                            val point = HistoryPoint(sample.timestamp, sample.timestamp, 1, sample.cellsV)
                            val aggregate = previous?.combine(point) ?: point
                            val values = ContentValues().apply {
                                put("device", state.device.address); put("t", bucket); put("n", sample.cellsV.size)
                                put("first", aggregate.first); put("last", aggregate.last); put("count", aggregate.count)
                                put("mean", encodeMean(aggregate.mean))
                                put("low", HistoryPolicy.encode(aggregate.low)); put("high", HistoryPolicy.encode(aggregate.high))
                                put("gap", if (aggregate.hasGap) 1 else 0)
                            }
                            check(db.insertWithOnConflict("minute", null, values,
                                SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                        }
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                    // Retention is based on current wall clock, not a caller-supplied measurement.
                    val now = System.currentTimeMillis()
                    if (now - lastCleanup >= 3_600_000L || now < lastCleanup) {
                        db.delete("raw", "t<?", arrayOf((now - HistoryPolicy.DAY).toString()))
                        db.delete("minute", "t<?", arrayOf(HistoryPolicy.cutoff(now).toString()))
                        lastCleanup = now
                    }
                    recordingFailed = false
                }.onFailure { recordingFailed = true }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            recordingFailed = true
        }
    }

    data class Result(val points: List<HistoryPoint>, val minuteResolution: Boolean)

    /** Separate reader thread + WAL let writes continue during a long-range query.
     * Cancellation is checked while streaming so obsolete gesture queries are cheap to drop. */
    fun query(device: String, from: Long, to: Long, cancelled: () -> Boolean = { false }): Result {
        val now = System.currentTimeMillis()
        val start = from.coerceAtLeast(HistoryPolicy.cutoff(now))
        val end = to.coerceAtMost(now)
        val minute = start < now - HistoryPolicy.DAY || end - start > 6 * 3_600_000L
        if (end < start) return Result(emptyList(), minute)
        val reducer = HistoryReducer(start, end)
        val sql = if (minute) "SELECT first,last,count,mean,low,high,gap FROM minute WHERE device=? AND t>=? AND t<=? ORDER BY t,first"
            else "SELECT t,cells FROM raw WHERE device=? AND t>=? AND t<=? ORDER BY t"
        val queryStart = if (minute) start / HistoryPolicy.ARCHIVE_BUCKET *
            HistoryPolicy.ARCHIVE_BUCKET else start
        readableDatabase.rawQuery(sql, arrayOf(device, queryStart.toString(), end.toString())).use { cursor ->
            while (!cancelled() && cursor.moveToNext()) {
                val point = if (minute) minutePoint(cursor)
                    else HistoryPoint(cursor.getLong(0), cursor.getLong(0), 1, HistoryPolicy.decode(cursor.getBlob(1)))
                if (point.last >= start && point.first <= end) reducer.add(point)
            }
        }
        return Result(reducer.points(), minute)
    }

    private fun minutePoint(cursor: Cursor) = HistoryPoint(
        cursor.getLong(0), cursor.getLong(1), cursor.getInt(2), decodeMean(cursor.getBlob(3)),
        HistoryPolicy.decode(cursor.getBlob(4)), HistoryPolicy.decode(cursor.getBlob(5)), cursor.getInt(6) != 0,
    )

    private fun encodeMean(values: List<Float>): ByteArray = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putFloat(it) } }.array()

    private fun decodeMean(bytes: ByteArray): List<Float> {
        require(bytes.size % 4 == 0 && bytes.size in 12..128)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return List(bytes.size / 4) { buffer.float }
    }

    companion object {
        @Volatile private var instance: HistoryStore? = null
        fun get(context: Context): HistoryStore = instance ?: synchronized(this) {
            instance ?: HistoryStore(context).also { instance = it }
        }
    }
}
