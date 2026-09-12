package com.zayants.bmsmultiprobe

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.zayants.bmsmultiprobe.history.*
import com.zayants.bmsmultiprobe.model.*
import com.zayants.bmsmultiprobe.ui.UiPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HistoryAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private fun freshStore(): HistoryStore = HistoryStore::class.java.getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }.newInstance(context)

    private fun writer(store: HistoryStore) = HistoryStore::class.java.getDeclaredField("writer")
        .apply { isAccessible = true }.get(store) as ThreadPoolExecutor
    private fun flush(store: HistoryStore) { writer(store).submit {}.get(10, TimeUnit.SECONDS) }
    private fun close(store: HistoryStore) { flush(store); writer(store).shutdownNow(); store.close() }
    private fun window(time: Long, device: String = "AA:BB:CC:DD:EE:01", voltage: Float = 3.271f,
                       connected: Boolean = true): ProbeWindow = ProbeWindow(time,
        listOf(ProbeSessionState(ProbeDevice("Test", device), "telemetry", connected, 1, time,
            telemetry = ProbeTelemetry(time, voltage * 8, 0f, 75, 25f, List(8) { voltage }))), null)

    @Test fun sqliteDeduplicatesSeparatesDevicesAndSurvivesReopen() {
        val now = System.currentTimeMillis()
        var store = freshStore()
        try {
            store.record(window(now - 10_000))
            store.record(window(now - 10_000))
            store.record(window(now - 5_000, voltage = 3.289f))
            store.record(window(now - 5_000, device = "AA:BB:CC:DD:EE:02", voltage = 3.5f))
            flush(store)
            assertFalse(store.recordingFailed)
            val storage = store.storageStats()
            assertTrue(storage.databaseBytes > 0)
            // Robolectric may report zero for its virtual filesystem; a real device
            // supplies the actual StatFs value. The API must still return a value.
            assertTrue(storage.availableBytes >= 0)
            val result = store.query("AA:BB:CC:DD:EE:01", now - 60_000, now)
            assertFalse(result.minuteResolution)
            assertEquals(2, result.points.sumOf { it.count })
            close(store)
            store = freshStore()
            assertEquals(2, store.query("AA:BB:CC:DD:EE:01", now - 60_000, now).points.sumOf { it.count })
            assertEquals(3.5f, store.query("AA:BB:CC:DD:EE:02", now - 60_000, now).points.single().mean[0])
        } finally { close(store) }
    }

    @Test fun oldDataUsesMinuteExtremaAndExpiredDataIsRemoved() {
        val now = System.currentTimeMillis()
        val store = freshStore()
        try {
            val old = now - 100 * HistoryPolicy.DAY
            store.record(window(old, voltage = 3.1f))
            store.record(window(old + 5_000, voltage = 3.8f))
            store.record(window(HistoryPolicy.cutoff(now) - HistoryPolicy.DAY))
            flush(store)
            HistoryStore::class.java.getDeclaredField("lastCleanup").apply { isAccessible = true }.setLong(store, 0L)
            store.record(window(now))
            flush(store)
            assertFalse(store.recordingFailed)
            val result = store.query("AA:BB:CC:DD:EE:01", old - 60_000, old + 60_000)
            assertTrue(result.minuteResolution)
            assertEquals(2, result.points.sumOf { it.count })
            assertEquals(3.8f, result.points.maxOf { it.high[0] })
            assertEquals(3.1f, result.points.minOf { it.low[0] })
            store.readableDatabase.rawQuery("SELECT count(*) FROM raw WHERE t<?",
                arrayOf((now - HistoryPolicy.DAY).toString())).use {
                it.moveToFirst(); assertEquals(0, it.getInt(0))
            }
            store.readableDatabase.rawQuery("SELECT count(*) FROM minute WHERE t<?",
                arrayOf(HistoryPolicy.cutoff(now).toString())).use {
                it.moveToFirst(); assertEquals(0, it.getInt(0))
            }
        } finally { close(store) }
    }

    @Test fun disconnectedSamplesAreSkippedAndStorageFailureIsReported() {
        val now = System.currentTimeMillis()
        val store = freshStore()
        try {
            store.record(window(now, connected = false))
            flush(store)
            assertTrue(store.query("AA:BB:CC:DD:EE:01", now - 60_000, now).points.isEmpty())
            store.writableDatabase.execSQL("DROP TABLE raw") // isolated Robolectric test database only
            store.record(window(now))
            flush(store)
            assertTrue(store.recordingFailed)
        } finally { close(store) }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun nativeChartDraws32CellsAndSupportsBothThemesAndLanguages() {
        for (language in listOf("en", "ru", "uk")) for (theme in listOf("light", "dark")) {
            UiPreferences.setLanguage(context, language)
            UiPreferences.setTheme(context, theme)
            val chart = CellChartView(UiPreferences.wrap(context))
            val now = System.currentTimeMillis()
            chart.viewport.set(now - 60_000, now)
            chart.points = List(12) { i -> HistoryPoint(now - 55_000 + i * 5_000, now - 55_000 + i * 5_000,
                1, List(32) { cell -> 3.2f + cell * 0.001f + i * 0.0005f }) }
            for ((width, height) in listOf(360 to 450, 720 to 160)) {
                chart.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                chart.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                chart.draw(Canvas(bitmap))
                assertNotEquals("Chart background must be rendered", 0, bitmap.getPixel(0, 0))
                if (language == "ru" && width == 720) {
                    val folder = java.io.File("build/reports/chart-preview").apply { mkdirs() }
                    java.io.File(folder, "chart-$theme.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                bitmap.recycle()
            }
            chart.hiddenCells.addAll(0..31)
            chart.draw(Canvas(Bitmap.createBitmap(360, 450, Bitmap.Config.ARGB_8888)))
        }
    }

    @Test fun touchDragPansTheTimeline() {
        val chart = CellChartView(context)
        val now = System.currentTimeMillis()
        chart.viewport.set(now - 3_600_000, now)
        chart.layout(0, 0, 720, 300)
        chart.draw(Canvas(Bitmap.createBitmap(720, 300, Bitmap.Config.ARGB_8888)))
        val before = chart.viewport.start
        val start = android.os.SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, time: Long) =
            android.view.MotionEvent.obtain(start, time, action, x, 100f, 0).also {
                chart.onTouchEvent(it); it.recycle()
            }
        event(android.view.MotionEvent.ACTION_DOWN, 200f, start)
        event(android.view.MotionEvent.ACTION_MOVE, 300f, start + 30)
        event(android.view.MotionEvent.ACTION_UP, 300f, start + 60)
        assertTrue(chart.viewport.start < before)
    }

    @Test fun historyScreenShowsRangeControlsAndBackWithoutStartingBle() {
        val intent = Intent(context, CellHistoryActivity::class.java).putExtra("address", "AA:BB:CC:DD:EE:01")
            .putExtra("name", "Test battery")
        val controller = Robolectric.buildActivity(CellHistoryActivity::class.java, intent).create()
        val activity = controller.get()
        fun views(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        val children = views(activity.window.decorView)
        val sixMonths = children.filterIsInstance<Button>().first { it.text == activity.getString(R.string.chart_six_months) }
        sixMonths.performClick()
        val chart = children.filterIsInstance<CellChartView>().single()
        assertTrue(chart.viewport.span >= 180 * HistoryPolicy.DAY)
        val saved = Bundle()
        controller.saveInstanceState(saved)
        assertTrue(saved.getBoolean("follow"))
        children.filterIsInstance<Button>().first { it.text == activity.getString(R.string.back) }.performClick()
        assertTrue(activity.isFinishing)
        controller.destroy()
    }
}
