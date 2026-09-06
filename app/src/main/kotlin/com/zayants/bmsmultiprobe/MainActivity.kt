package com.zayants.bmsmultiprobe

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zayants.bmsmultiprobe.model.ProbeDevice
import com.zayants.bmsmultiprobe.model.ProbeSessionState
import com.zayants.bmsmultiprobe.model.ProbeWindow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), MultiBmsService.Observer {
    private var service: MultiBmsService? = null
    private var bound = false
    private var setupRequested = false
    private val selected = linkedMapOf<String, ProbeDevice>()
    private var scanned = emptyList<ProbeDevice>()
    private val scanOrder = mutableListOf<String>()
    private var scanActive = false

    private lateinit var scanButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var backButton: Button
    private lateinit var scanStatus: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var windowSummary: TextView
    private lateinit var sessionList: GridLayout
    private lateinit var setupPage: View
    private lateinit var dashboardPage: View
    private var latestSessions = emptyList<ProbeSessionState>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MultiBmsService.LocalBinder).service()
            service?.addObserver(this@MainActivity)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        ensurePermissionsAndStart()
    }

    override fun onStart() {
        super.onStart()
        if (hasRequiredPermissions()) startAndBindService()
    }

    override fun onStop() {
        if (bound) {
            service?.removeObserver(this)
            unbindService(connection)
            bound = false
        }
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && hasRequiredPermissions()) {
            startAndBindService()
        } else {
            scanStatus.text = "Bluetooth and location permissions are required"
        }
    }

    override fun onScanChanged(devices: List<ProbeDevice>, scanning: Boolean) {
        runOnUiThread {
            if (scanning && !scanActive) scanOrder.clear()
            scanActive = scanning
            devices.forEach { device ->
                if (device.address !in scanOrder) scanOrder += device.address
                if (selected.containsKey(device.address)) selected[device.address] = device
            }
            val byAddress = devices.associateBy(ProbeDevice::address)
            val stableDevices = scanOrder.mapNotNull(byAddress::get)
            val visibleListChanged = scanned.map { it.address to it.name } !=
                stableDevices.map { it.address to it.name }
            scanned = stableDevices
            scanStatus.text = when {
                scanning -> "Scanning: ${devices.size} BLE devices found"
                devices.isEmpty() -> "Press SCAN, then select up to 4 BMS devices"
                else -> "Found ${devices.size}; selected ${selected.size}/4"
            }
            scanButton.isEnabled = !scanning
            if (visibleListChanged) renderDevices()
        }
    }

    override fun onSessionsChanged(sessions: List<ProbeSessionState>) {
        runOnUiThread {
            latestSessions = sessions
            backButton.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
            renderSessions(sessions)
            if (sessions.isEmpty()) {
                setupRequested = true
                showSetup()
            } else if (!setupRequested) {
                showDashboard()
            }
        }
    }

    override fun onWindow(window: ProbeWindow) {
        runOnUiThread {
            val fresh = window.sessions.count { it.telemetry != null }
            val skew = window.packetSkewMs?.let { "$it ms" } ?: "—"
            val url = service?.gatewayUrl() ?: "Wi-Fi unavailable"
            windowSummary.text =
                "5 s sample: ${clock(window.sampledAt)}  •  fresh $fresh/${window.sessions.size}  •  " +
                    "packet skew $skew\nAPI: $url"
        }
    }

    private fun buildUi() {
        val setupRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(Color.rgb(245, 243, 241))
        }
        setupRoot.addView(TextView(this).apply {
            text = "BMS MULTI PROBE"
            textSize = 25f
            setTextColor(Color.rgb(215, 7, 18))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        setupRoot.addView(TextView(this).apply {
            text = "Connection setup • version 0.3.7\n" +
                "Read requests only (device info + telemetry); no BMS settings writes\n" +
                "Dashboard has six visual slots; stable BLE test limit remains four connections"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(12))
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanButton = actionButton("SCAN") { service?.scan() }
        connectButton = actionButton("CONNECT") {
            if (selected.isEmpty()) {
                scanStatus.text = "Select up to 4 BMS devices first"
            } else {
                setupRequested = false
                service?.connect(selected.values.toList())
            }
        }
        disconnectButton = actionButton("DISCONNECT") {
            service?.disconnectAll()
            setupRequested = true
            selected.clear()
            renderDevices()
        }
        backButton = actionButton("← BACK") { returnToDashboard() }.apply {
            visibility = View.GONE
        }
        actions.addView(scanButton, weightParams())
        actions.addView(connectButton, weightParams())
        actions.addView(disconnectButton, weightParams())
        actions.addView(backButton, weightParams())
        setupRoot.addView(actions)

        scanStatus = label("Press SCAN, then select up to 4 BMS devices", 14f)
        scanStatus.setPadding(0, dp(12), 0, dp(8))
        setupRoot.addView(scanStatus)

        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupRoot.addView(deviceList)

        setupPage = ScrollView(this).apply { addView(setupRoot) }

        val dashboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(Color.rgb(9, 14, 27))
        }
        val dashboardHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dashboardHeader.addView(label("BMS FLEET", 22f).apply {
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dashboardHeader.addView(label("6 MONITORS", 11f).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(165, 228, 255))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = rounded(Color.rgb(25, 63, 90), dp(12))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, dp(8), 0)
        })
        dashboardHeader.addView(actionButton("SETUP") {
            setupRequested = true
            showSetup()
        }, LinearLayout.LayoutParams(dp(104), dp(44)))
        dashboard.addView(dashboardHeader)

        windowSummary = label("5 s sample: waiting", 13f).apply {
            setTextColor(Color.rgb(126, 232, 170))
            setPadding(0, dp(4), 0, dp(5))
        }
        dashboard.addView(windowSummary)

        sessionList = GridLayout(this).apply {
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            columnCount = if (landscape) 3 else 2
            rowCount = if (landscape) 2 else 3
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        dashboard.addView(sessionList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        dashboardPage = dashboard

        setContentView(FrameLayout(this).apply {
            addView(setupPage, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(dashboardPage, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        })
        showSetup()
    }

    private fun renderDevices() {
        deviceList.removeAllViews()
        scanned.forEach { device ->
            val isSelected = selected.containsKey(device.address)
            deviceList.addView(Button(this).apply {
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                stateListAnimator = null
                minHeight = dp(66)
                text = if (isSelected) {
                    "✓  SELECTED  •  ${device.name}\n     ${device.address}  •  ${device.rssi} dBm"
                } else {
                    "○  ${device.name}\n     ${device.address}  •  ${device.rssi} dBm"
                }
                setTextColor(if (isSelected) Color.WHITE else Color.rgb(35, 45, 55))
                background = if (isSelected) {
                    rounded(Color.rgb(25, 119, 92), dp(10), Color.rgb(60, 214, 135))
                } else {
                    rounded(Color.WHITE, dp(10), Color.rgb(180, 187, 194))
                }
                setOnClickListener {
                    if (isSelected) {
                        selected.remove(device.address)
                    } else if (selected.size < 4) {
                        selected[device.address] = device
                    } else {
                        scanStatus.text = "Maximum is 4 persistent connections"
                    }
                    renderDevices()
                    connectButton.text = "CONNECT ${selected.size}"
                    scanStatus.text = "Found ${scanned.size}; selected ${selected.size}/4"
                }
            }, matchParams().apply { setMargins(0, dp(3), 0, dp(3)) })
        }
    }

    private fun renderSessions(sessions: List<ProbeSessionState>) {
        sessionList.removeAllViews()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        (0 until DISPLAY_SLOTS).forEach { index ->
            val state = sessions.getOrNull(index)
            if (state == null) {
                sessionList.addView(emptySocCard(index), gridParams())
                return@forEach
            }
            val sample = state.telemetry
            val alarms = sample?.alarms.orEmpty()
            val accent = when {
                alarms.isNotEmpty() -> Color.rgb(255, 91, 91)
                !state.transportConnected -> Color.rgb(255, 190, 92)
                sample == null -> Color.rgb(255, 190, 92)
                else -> Color.rgb(60, 214, 135)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(9))
                background = rounded(Color.rgb(22, 31, 51), dp(16), accent)
                if (alarms.isNotEmpty()) {
                    isClickable = true
                    isFocusable = true
                    contentDescription = "BMS ${index + 1}: alarm. Tap to view causes."
                    setOnClickListener { showAlarmDialog(index, state, alarms) }
                }
            }
            val top = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            top.addView(label("BMS ${index + 1}  ${state.device.name}", 14f).apply {
                setTextColor(Color.WHITE)
                maxLines = 1
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(label(if (alarms.isNotEmpty()) "ALARM" else state.status.uppercase(), 9f).apply {
                gravity = Gravity.CENTER
                setTextColor(if (alarms.isNotEmpty()) Color.WHITE else Color.rgb(9, 14, 27))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = rounded(accent, dp(8))
                setPadding(dp(6), dp(3), dp(6), dp(3))
            })
            card.addView(top)
            card.addView(label(
                sample?.let { "${it.socPercent}%" } ?: "—%",
                if (landscape) 36f else 38f,
            ).apply {
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (sample == null) Color.rgb(139, 153, 178) else accent)
            }, if (landscape) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            })
            if (landscape) {
                card.addView(metricRow(
                    metric("VOLT", sample?.let { "%.2f V".format(it.packVoltageV) } ?: "—"),
                    metric("CURRENT", sample?.let { "%.2f A".format(it.currentA) } ?: "—"),
                    metric("TEMP", sample?.let { "%.1f °C".format(it.temperatureC) } ?: "—"),
                ))
            } else {
                card.addView(metricRow(
                    metric("VOLTAGE", sample?.let { "%.2f V".format(it.packVoltageV) } ?: "—"),
                    metric("CURRENT", sample?.let { "%.2f A".format(it.currentA) } ?: "—"),
                ))
                card.addView(metricRow(
                    metric("POWER", sample?.let { "%.0f W".format(it.packVoltageV * it.currentA) } ?: "—"),
                    metric("TEMP", sample?.let { "%.1f °C".format(it.temperatureC) } ?: "—"),
                ))
            }
            card.addView(label(
                when {
                    alarms.isNotEmpty() -> "ALARM • tap card for details"
                    sample != null -> "${sample.cellsV.size} cells  •  ${state.packetCount} packets"
                    else -> "Waiting for telemetry"
                },
                10f,
            ).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(154, 171, 195))
                setPadding(0, dp(6), 0, 0)
            })
            sessionList.addView(card, gridParams())
        }
    }

    private fun emptySocCard(index: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = rounded(Color.rgb(22, 31, 51), dp(16), Color.rgb(53, 68, 94))
        addView(label("BMS ${index + 1}", 14f).apply {
            setTextColor(Color.rgb(199, 211, 229))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(label("READY", 10f).apply {
            setTextColor(Color.rgb(121, 143, 175))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        })
        addView(label("—%", 38f).apply {
            setTextColor(Color.rgb(99, 116, 145))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(label("Slot available", 12f).apply { setTextColor(Color.rgb(121, 143, 175)) })
    }

    private fun metricRow(vararg metrics: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        metrics.forEach { metric ->
            addView(metric, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun showAlarmDialog(index: Int, state: ProbeSessionState, alarms: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("BMS ${index + 1}: alarm")
            .setMessage(buildString {
                append(state.device.name)
                append("\n\n")
                alarms.forEach { append("• ").append(it).append('\n') }
                append("\nSource: JK BMS runtime alarm flag. Monitoring is read-only.")
            })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun metric(title: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 8f).apply {
            setTextColor(Color.rgb(126, 150, 185))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(label(value, 13f).apply {
            setTextColor(Color.rgb(238, 244, 253))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius.toFloat()
        setColor(color)
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun gridParams() = GridLayout.LayoutParams().apply {
        width = 0
        height = 0
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    private fun showDashboard() {
        setupPage.visibility = View.GONE
        dashboardPage.visibility = View.VISIBLE
        applyImmersiveMode(true)
    }

    private fun returnToDashboard() {
        if (latestSessions.isEmpty()) return
        setupRequested = false
        showDashboard()
    }

    private fun showSetup() {
        dashboardPage.visibility = View.GONE
        setupPage.visibility = View.VISIBLE
        applyImmersiveMode(false)
    }

    @Suppress("DEPRECATION")
    private fun applyImmersiveMode(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                if (enabled) {
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                }
            }
        } else {
            window.decorView.systemUiVisibility = if (enabled) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    private fun ensurePermissionsAndStart() {
        if (hasRequiredPermissions()) {
            startAndBindService()
        } else {
            requestPermissions(requiredPermissions(), PERMISSION_REQUEST)
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, MultiBmsService::class.java)
        startForegroundService(intent)
        if (!bound) bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // MIUI on Android 12 can register a BLE scan successfully but suppress every
        // result unless precise location is granted. Android 13+ uses Nearby Devices.
        if (Build.VERSION.SDK_INT <= 32) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun hasRequiredPermissions(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun actionButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 11f
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(30, 30, 30))
    }

    private fun matchParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun weightParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        setMargins(dp(2), 0, dp(2), 0)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun clock(timestamp: Long): String = TIME_FORMAT.format(Date(timestamp))

    companion object {
        private const val PERMISSION_REQUEST = 71
        private const val DISPLAY_SLOTS = 6
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}
