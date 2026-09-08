package com.zayants.bmsmultiprobe.history

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.zayants.bmsmultiprobe.MultiBmsService
import com.zayants.bmsmultiprobe.R
import com.zayants.bmsmultiprobe.model.ProbeSessionState
import com.zayants.bmsmultiprobe.ui.UiPreferences
import com.zayants.bmsmultiprobe.ui.UiText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CellHistoryActivity : Activity(), MultiBmsService.Observer {
    private lateinit var address: String
    private lateinit var deviceName: String
    private lateinit var chart: CellChartView
    private lateinit var summary: TextView
    private lateinit var legend: LinearLayout
    private lateinit var alarmButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val reader = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "bms-history-reader") },
        ThreadPoolExecutor.DiscardOldestPolicy())
    private var active = false
    private var followLatest = true
    private var service: MultiBmsService? = null
    private var registered = false
    private var alarms = emptyList<String>()
    private var alarmTime: Long? = null
    private var lastResult: HistoryStore.Result? = null
    private var readFailed = false
    private var legendCount = 0
    private var lastQueryAt = 0L
    private val store by lazy { HistoryStore.get(applicationContext) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MultiBmsService.LocalBinder).service()
            service?.addObserver(this@CellHistoryActivity)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val load = Runnable {
        if (!active) return@Runnable
        val request = generation.get()
        val from = chart.viewport.start
        val to = chart.viewport.end
        reader.execute {
            val result = runCatching { store.query(address, from, to) { generation.get() != request } }
            handler.post {
                if (!active || generation.get() != request) return@post
                readFailed = result.isFailure
                result.onSuccess {
                    lastResult = it
                    chart.points = it.points
                    updateLegend(it.points.maxOfOrNull { point -> point.mean.size } ?: legendCount)
                }
                if (readFailed) chart.points = emptyList()
                updateSummary()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            updateBounds()
            val interval = when {
                chart.viewport.span > 7 * HistoryPolicy.DAY -> 60_000L
                chart.viewport.span > HistoryPolicy.DAY -> 15_000L
                else -> 5_000L
            }
            if (followLatest && System.currentTimeMillis() - lastQueryAt >= interval) requestData()
            else updateSummary()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(UiPreferences.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra("address").orEmpty()
        if (address.isBlank()) { finish(); return }
        deviceName = intent.getStringExtra("name") ?: address
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        savedInstanceState?.let {
            chart.viewport.set(it.getLong("from", chart.viewport.start), it.getLong("to", chart.viewport.end))
            followLatest = it.getBoolean("follow", true)
            chart.hiddenCells.addAll(it.getIntArray("hidden")?.toList().orEmpty())
        }
        chart.onRangeChanged = {
            followLatest = chart.viewport.end >= System.currentTimeMillis() - 1_000
            requestData()
        }
        updateSummary()
    }

    override fun onStart() {
        super.onStart()
        if (!::chart.isInitialized) return
        active = true
        // Bind to the already running monitor; never restart or reconnect a BMS for a chart.
        registered = bindService(Intent(this, MultiBmsService::class.java), connection, 0)
        requestData()
        handler.post(tick)
    }

    override fun onStop() {
        active = false
        generation.incrementAndGet()
        handler.removeCallbacks(load)
        handler.removeCallbacks(tick)
        service?.removeObserver(this)
        if (registered) unbindService(connection)
        registered = false; service = null
        super.onStop()
    }

    override fun onDestroy() { reader.shutdownNow(); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::chart.isInitialized) {
            outState.putLong("from", chart.viewport.start); outState.putLong("to", chart.viewport.end)
            outState.putBoolean("follow", followLatest)
            outState.putIntArray("hidden", chart.hiddenCells.toIntArray())
        }
        super.onSaveInstanceState(outState)
    }

    override fun onSessionsChanged(sessions: List<ProbeSessionState>) {
        runOnUiThread {
            val state = sessions.firstOrNull { it.device.address == address }
            alarms = state?.telemetry?.alarms.orEmpty()
            alarmTime = state?.telemetry?.timestamp
            alarmButton.visibility = if (alarms.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.ui_background))
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(button(getString(R.string.back)) { finish() })
        header.addView(label(deviceName, 16f).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        alarmButton = button(getString(R.string.alarm)) { showAlarms() }.apply {
            setTextColor(getColor(R.color.ui_alarm)); visibility = View.GONE
        }
        header.addView(alarmButton)
        root.addView(header)

        val ranges = LinearLayout(this)
        val choices = listOf(R.string.chart_hour to 3_600_000L, R.string.chart_day to HistoryPolicy.DAY,
            R.string.chart_week to 7 * HistoryPolicy.DAY, R.string.chart_month to 30 * HistoryPolicy.DAY,
            R.string.chart_six_months to 0L)
        choices.forEach { (title, span) ->
            ranges.addView(button(getString(title)) {
                val now = System.currentTimeMillis()
                chart.viewport.lower = HistoryPolicy.cutoff(now); chart.viewport.upper = now
                chart.viewport.set(if (span == 0L) chart.viewport.lower else now - span, now)
                followLatest = true
                chart.invalidate(); requestData()
            })
        }
        ranges.addView(button(getString(R.string.chart_now)) {
            followLatest = true; updateBounds(); requestData()
        })
        ranges.addView(button("−") { chart.viewport.zoom(0.5, 0.5); chart.changed() }.apply {
            contentDescription = getString(R.string.chart_zoom_out)
        })
        ranges.addView(button("+") { chart.viewport.zoom(2.0, 0.5); chart.changed() }.apply {
            contentDescription = getString(R.string.chart_zoom_in)
        })
        ranges.addView(button("←") { chart.viewport.pan(-0.5); chart.changed() }.apply {
            contentDescription = getString(R.string.chart_earlier)
        })
        ranges.addView(button("→") { chart.viewport.pan(0.5); chart.changed() }.apply {
            contentDescription = getString(R.string.chart_later)
        })
        root.addView(HorizontalScrollView(this).apply { addView(ranges) })
        summary = label(getString(R.string.loading), 11f).apply { maxLines = 3 }
        root.addView(summary)
        chart = CellChartView(this)
        root.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        legend = LinearLayout(this)
        root.addView(HorizontalScrollView(this).apply { addView(legend) })
        root.addView(label(getString(R.string.chart_gestures), 11f))
        setContentView(root)
    }

    private fun updateBounds() {
        val span = chart.viewport.span
        val now = System.currentTimeMillis()
        chart.viewport.lower = HistoryPolicy.cutoff(now); chart.viewport.upper = now
        if (followLatest) chart.viewport.set(now - span, now)
        else chart.viewport.set(chart.viewport.start, chart.viewport.end)
        chart.invalidate()
    }

    private fun requestData() {
        if (!active) return
        generation.incrementAndGet()
        lastQueryAt = System.currentTimeMillis()
        handler.removeCallbacks(load)
        handler.postDelayed(load, 180)
        updateSummary()
    }

    private fun updateSummary() {
        val format = SimpleDateFormat("dd MMM yyyy HH:mm", resources.configuration.locales[0])
        val range = format.format(Date(chart.viewport.start)) + " — " + format.format(Date(chart.viewport.end))
        val description = when {
            readFailed -> getString(R.string.chart_read_error)
            store.recordingFailed -> getString(R.string.chart_record_error)
            lastResult == null -> getString(R.string.loading)
            lastResult!!.points.isEmpty() -> getString(R.string.chart_empty)
            lastResult!!.minuteResolution -> getString(R.string.chart_minutes)
            else -> getString(R.string.chart_raw)
        }
        summary.text = getString(R.string.chart_summary, range, description)
        summary.setTextColor(getColor(if (readFailed || store.recordingFailed) R.color.ui_alarm else R.color.ui_muted))
    }

    private fun updateLegend(count: Int) {
        if (legendCount == count && legend.childCount > 0) return
        legendCount = count
        legend.removeAllViews()
        repeat(count) { index ->
            legend.addView(CheckBox(this).apply {
                text = getString(R.string.chart_cell, index + 1)
                setTextColor(chart.cellColor(index))
                isChecked = index !in chart.hiddenCells
                setOnCheckedChangeListener { _, checked ->
                    if (checked) chart.hiddenCells.remove(index) else chart.hiddenCells.add(index)
                    chart.invalidate()
                }
            })
        }
    }

    private fun showAlarms() {
        val time = alarmTime?.let {
            SimpleDateFormat("dd MMM yyyy HH:mm:ss", resources.configuration.locales[0]).format(Date(it))
        }.orEmpty()
        AlertDialog.Builder(this).setTitle(getString(R.string.alarm) + " · " + deviceName)
            .setMessage(time + "\n\n" + alarms.joinToString("\n") { "• " + UiText.alarm(this, it) } +
                "\n\n" + getString(R.string.alarm_source))
            .setPositiveButton(R.string.close, null).show()
    }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title; textSize = 11f; isAllCaps = false
        setOnClickListener { action() }
    }
    private fun label(title: String, size: Float) = TextView(this).apply {
        text = title; textSize = size; setTextColor(getColor(R.color.ui_text))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
