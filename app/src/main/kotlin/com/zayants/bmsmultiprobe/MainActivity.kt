package com.zayants.bmsmultiprobe

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.net.Uri
import android.text.TextUtils
import com.zayants.bmsmultiprobe.ui.UiPreferences
import com.zayants.bmsmultiprobe.ui.UiText
import com.zayants.bmsmultiprobe.history.CellHistoryActivity
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
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
import com.zayants.bmsmultiprobe.history.HistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), MultiBmsService.Observer {
    private var service: MultiBmsService? = null
    private var bound = false
    private var binding = false
    private var setupRequested = false
    private val selected = linkedMapOf<String, ProbeDevice>()
    private var scanned = emptyList<ProbeDevice>()
    private val scanOrder = mutableListOf<String>()
    private var scanActive = false
    private var pendingScan = false

    private lateinit var scanButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var backButton: Button
    private lateinit var scanStatus: TextView
    private lateinit var accessStatus: TextView
    private lateinit var historyStatus: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var windowSummary: TextView
    private lateinit var sessionList: GridLayout
    private lateinit var setupPage: View
    private lateinit var dashboardPage: View
    private var latestSessions = emptyList<ProbeSessionState>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MultiBmsService.LocalBinder).service()
            binding = false
            bound = true
            service?.refreshAppearance()
            service?.addObserver(this@MainActivity)
            if (pendingScan) {
                pendingScan = false
                service?.scan()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            binding = false
            service = null
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiPreferences.wrap(newBase))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("setup", setupRequested)
        outState.putBoolean("scanning", scanActive)
        outState.putStringArrayList("scanOrder", ArrayList(scanOrder))
        outState.putStringArrayList("selectedAddresses", ArrayList(selected.keys))
        outState.putStringArrayList("selectedNames", ArrayList(selected.values.map { it.name }))
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { saved ->
            setupRequested = saved.getBoolean("setup")
            scanActive = saved.getBoolean("scanning")
            scanOrder.addAll(saved.getStringArrayList("scanOrder").orEmpty())
            val names = saved.getStringArrayList("selectedNames").orEmpty()
            saved.getStringArrayList("selectedAddresses").orEmpty().forEachIndexed { index, address ->
                selected[address] = ProbeDevice(names.getOrElse(index) { "JK BMS" }, address)
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        ensurePermissionsAndStart()
    }

    override fun onStart() {
        super.onStart()
        if (hasRequiredPermissions()) startAndBindService()
    }

    override fun onStop() {
        if (bound || binding) {
            service?.removeObserver(this)
            unbindService(connection)
            bound = false
            binding = false
            service = null
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
            refreshDiagnostics()
        } else {
            pendingScan = false
            scanStatus.text = getString(R.string.permissions_required)
            showPermissionHelp()
        }
    }

    override fun onScanChanged(devices: List<ProbeDevice>, scanning: Boolean) {
        runOnUiThread {
            val finishedWithoutResults = scanActive && !scanning && devices.isEmpty()
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
                scanning -> getString(R.string.scanning, devices.size, selected.size)
                devices.isEmpty() -> getString(R.string.scan_hint)
                else -> getString(R.string.found_selected, devices.size, selected.size)
            }
            scanButton.isEnabled = !scanning
            if (visibleListChanged) renderDevices()
            if (finishedWithoutResults && setupPage.visibility == View.VISIBLE) showBleHelp()
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
            val skew = window.packetSkewMs?.let { getString(R.string.ms_value, it) } ?: "—"
            val url = service?.gatewayUrl() ?: getString(R.string.wifi_unavailable)
            windowSummary.text =
                getString(R.string.window_summary, clock(window.sampledAt), fresh, window.sessions.size, skew, url)
        }
    }

    private fun buildUi() {
        val setupRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(getColor(R.color.ui_background))
        }
        setupRoot.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 25f
            setTextColor(getColor(R.color.ui_accent))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        setupRoot.addView(TextView(this).apply {
            text = getString(R.string.setup_intro, getString(R.string.build_version))
            textSize = 13f
            setTextColor(getColor(R.color.ui_muted))
            setPadding(0, dp(4), 0, dp(12))
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scanButton = actionButton(getString(R.string.scan)) { startScanWithChecks() }
        connectButton = actionButton(if (selected.isEmpty()) getString(R.string.connect)
            else getString(R.string.connect_count, selected.size)) {
            if (selected.isEmpty()) {
                scanStatus.text = getString(R.string.select_first)
            } else {
                setupRequested = false
                service?.connect(selected.values.toList())
            }
        }
        disconnectButton = actionButton(getString(R.string.disconnect)) {
            service?.disconnectAll()
            setupRequested = true
            selected.clear()
            renderDevices()
            connectButton.text = getString(R.string.connect)
        }
        backButton = actionButton(getString(R.string.back)) { returnToDashboard() }.apply {
            visibility = View.GONE
        }
        actions.addView(scanButton, weightParams())
        actions.addView(connectButton, weightParams())
        setupRoot.addView(actions)
        setupRoot.addView(LinearLayout(this).apply {
            addView(disconnectButton, weightParams())
            addView(backButton, weightParams())
        })
        setupRoot.addView(LinearLayout(this).apply {
            addView(actionButton(getString(R.string.language)) { chooseLanguage() }, weightParams())
            addView(actionButton(getString(R.string.theme)) { chooseTheme() }, weightParams())
            addView(actionButton(getString(R.string.ble_access)) { showBleHelp() }, weightParams())
        })

        scanStatus = label(getString(R.string.scan_hint), 14f)
        scanStatus.setPadding(0, dp(12), 0, dp(8))
        setupRoot.addView(scanStatus)

        accessStatus = label("", 12f)
        accessStatus.setTextColor(getColor(R.color.ui_muted))
        setupRoot.addView(accessStatus)
        historyStatus = label("", 12f)
        historyStatus.setTextColor(getColor(R.color.ui_muted))
        historyStatus.setPadding(0, dp(2), 0, dp(6))
        setupRoot.addView(historyStatus)

        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setupRoot.addView(deviceList)

        setupPage = ScrollView(this).apply { addView(setupRoot) }

        val dashboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            setBackgroundColor(getColor(R.color.ui_background))
        }
        val dashboardHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dashboardHeader.addView(label(getString(R.string.fleet), 22f).apply {
            setTextColor(getColor(R.color.ui_text))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        dashboardHeader.addView(label(getString(R.string.monitors), 11f).apply {
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.ui_accent))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = rounded(getColor(R.color.ui_badge), dp(12))
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, dp(8), 0)
        })
        dashboardHeader.addView(actionButton(getString(R.string.setup)) {
            setupRequested = true
            showSetup()
        }, LinearLayout.LayoutParams(dp(104), dp(44)))
        dashboard.addView(dashboardHeader)

        windowSummary = label(getString(R.string.waiting_sample), 13f).apply {
            setTextColor(getColor(R.color.ui_success))
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
                    getString(R.string.device_selected, getString(R.string.selected), device.name, device.address, device.rssi)
                } else {
                    getString(R.string.device_row, device.name, device.address, device.rssi)
                }
                setTextColor(if (isSelected) getColor(R.color.ui_selected_text) else getColor(R.color.ui_text))
                background = if (isSelected) {
                    rounded(getColor(R.color.ui_selected), dp(10), getColor(R.color.ui_success))
                } else {
                    rounded(getColor(R.color.ui_surface), dp(10), getColor(R.color.ui_border))
                }
                setOnClickListener {
                    if (isSelected) {
                        selected.remove(device.address)
                    } else if (selected.size < 4) {
                        selected[device.address] = device
                    } else {
                        scanStatus.text = getString(R.string.max_devices)
                        return@setOnClickListener
                    }
                    renderDevices()
                    connectButton.text = getString(R.string.connect_count, selected.size)
                    scanStatus.text = getString(R.string.found_selected, scanned.size, selected.size)
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
                alarms.isNotEmpty() -> getColor(R.color.ui_alarm)
                !state.transportConnected -> getColor(R.color.ui_warning)
                sample == null -> getColor(R.color.ui_warning)
                else -> getColor(R.color.ui_success)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val padding = resources.getDimensionPixelSize(R.dimen.card_padding)
                setPadding(padding, padding, padding, padding)
                background = rounded(getColor(R.color.ui_surface), resources.getDimensionPixelSize(R.dimen.card_radius), accent)
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.chart_open, state.device.name)
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, CellHistoryActivity::class.java).apply {
                        putExtra("address", state.device.address)
                        putExtra("name", state.device.name)
                    })
                }
            }
            val top = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            top.addView(label(getString(R.string.bms_title, index + 1, state.device.name), sp(R.dimen.card_title_text)).apply {
                setTextColor(getColor(R.color.ui_text))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(label(if (alarms.isNotEmpty()) getString(R.string.alarm) else UiText.status(this, state.status), 9f).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(100)
                if (alarms.isNotEmpty()) {
                    contentDescription = getString(R.string.alarm_accessibility, index + 1)
                    setOnClickListener { showAlarmDialog(index, state, alarms) }
                }
                setTextColor(getColor(R.color.ui_badge_text))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = rounded(accent, dp(8))
                setPadding(dp(6), dp(3), dp(6), dp(3))
            })
            card.addView(top)
            card.addView(label(
                sample?.let { "${it.socPercent}%" } ?: "—%",
                sp(R.dimen.soc_text),
            ).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(16, sp(R.dimen.soc_text).toInt(), 1,
                    android.util.TypedValue.COMPLEX_UNIT_SP)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (sample == null) getColor(R.color.ui_muted) else accent)
            }, if (landscape) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            })
            if (landscape) {
                card.addView(metricRow(
                    metric(getString(R.string.voltage), sample?.let { getString(R.string.voltage_value, it.packVoltageV) } ?: "—"),
                    metric(getString(R.string.current), sample?.let { getString(R.string.current_value, it.currentA) } ?: "—"),
                    metric(getString(R.string.temperature), sample?.let { getString(R.string.temperature_value, it.temperatureC) } ?: "—"),
                ))
            } else {
                card.addView(metricRow(
                    metric(getString(R.string.voltage), sample?.let { getString(R.string.voltage_value, it.packVoltageV) } ?: "—"),
                    metric(getString(R.string.current), sample?.let { getString(R.string.current_value, it.currentA) } ?: "—"),
                ))
                card.addView(metricRow(
                    metric(getString(R.string.power), sample?.let { getString(R.string.power_value, it.packVoltageV * it.currentA) } ?: "—"),
                    metric(getString(R.string.temperature), sample?.let { getString(R.string.temperature_value, it.temperatureC) } ?: "—"),
                ))
            }
            card.addView(label(
                when {
                    alarms.isNotEmpty() -> getString(R.string.chart_alarm_hint)
                    sample != null -> getString(R.string.packet_summary, sample.cellsV.size, state.packetCount)
                    else -> getString(R.string.waiting_telemetry)
                },
                10f,
            ).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(getColor(R.color.ui_muted))
                setPadding(0, dp(6), 0, 0)
            })
            sessionList.addView(card, gridParams())
        }
    }

    private fun emptySocCard(index: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = rounded(getColor(R.color.ui_surface), resources.getDimensionPixelSize(R.dimen.card_radius), getColor(R.color.ui_border))
        addView(label(getString(R.string.bms_slot, index + 1), 14f).apply {
            setTextColor(getColor(R.color.ui_muted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(label(getString(R.string.ready), 10f).apply {
            setTextColor(getColor(R.color.ui_muted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        })
        addView(label("—%", sp(R.dimen.soc_text)).apply {
            setTextColor(getColor(R.color.ui_muted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(label(getString(R.string.slot_available), 12f).apply { setTextColor(getColor(R.color.ui_muted)) })
    }

    private fun metricRow(vararg metrics: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        metrics.forEach { metric ->
            addView(metric, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun showAlarmDialog(index: Int, state: ProbeSessionState, alarms: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.alarm_title, index + 1))
            .setMessage(buildString {
                append(state.device.name)
                append("\n\n")
                alarms.forEach { append("• ").append(UiText.alarm(this@MainActivity, it)).append('\n') }
                append("\n").append(getString(R.string.alarm_source))
            })
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun metric(title: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, sp(R.dimen.metric_label_text)).apply {
            setTextColor(getColor(R.color.ui_muted))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(label(value, sp(R.dimen.metric_value_text)).apply {
            setTextColor(getColor(R.color.ui_text))
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
        val gap = resources.getDimensionPixelSize(R.dimen.card_gap)
        setMargins(gap, gap, gap, gap)
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
        refreshDiagnostics()
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

    private fun startScanWithChecks() {
        if (!hasRequiredPermissions()) {
            pendingScan = true
            requestPermissions(requiredPermissions(), PERMISSION_REQUEST)
            return
        }
        val bluetooth = getSystemService(BluetoothManager::class.java).adapter
        if (bluetooth == null || !bluetooth.isEnabled) {
            AlertDialog.Builder(this)
                .setTitle(R.string.bluetooth_off_title)
                .setMessage(R.string.bluetooth_off_message)
                .setPositiveButton(R.string.open_bluetooth_settings) { _, _ ->
                    openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        if (Build.VERSION.SDK_INT <= 32 && !locationEnabled()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.location_off_title)
                .setMessage(R.string.location_off_message)
                .setPositiveButton(R.string.open_location_settings) { _, _ ->
                    openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                }
                .setNeutralButton(R.string.scan_anyway) { _, _ -> runScanWhenReady() }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        runScanWhenReady()
    }

    private fun runScanWhenReady() {
        service?.scan() ?: run {
            pendingScan = true
            startAndBindService()
        }
    }

    private fun showPermissionHelp() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_help_title)
            .setMessage(R.string.permission_help_message)
            .setPositiveButton(R.string.open_app_settings) { _, _ -> openAppSettings() }
            .setNeutralButton(R.string.open_location_settings) { _, _ ->
                openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBleHelp() {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle(R.string.scan_help_title)
            .setMessage(R.string.scan_help_message)
            .setPositiveButton(R.string.open_app_settings) { _, _ -> openAppSettings() }
            .setNeutralButton(R.string.open_bluetooth_settings) { _, _ ->
                openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            }
            .setNegativeButton(R.string.open_location_settings) { _, _ ->
                openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            }
            .show()
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }.onFailure { openSettings(Settings.ACTION_SETTINGS) }
    }

    private fun openSettings(action: String) {
        runCatching { startActivity(Intent(action)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun locationEnabled(): Boolean {
        val manager = getSystemService(LocationManager::class.java)
        return if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled else {
            Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    @Suppress("MissingPermission")
    private fun refreshDiagnostics() {
        if (!::accessStatus.isInitialized || !::historyStatus.isInitialized) return
        val missing = requiredPermissions().count {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        accessStatus.text = when {
            missing > 0 -> getString(R.string.access_missing, missing)
            getSystemService(BluetoothManager::class.java).adapter?.isEnabled != true ->
                getString(R.string.access_bluetooth_off)
            Build.VERSION.SDK_INT <= 32 && !locationEnabled() ->
                getString(R.string.access_location_off)
            else -> getString(R.string.access_ready)
        }
        val storage = HistoryStore.get(this).storageStats()
        historyStatus.text = getString(R.string.history_storage,
            formatBytes(storage.databaseBytes), formatBytes(storage.availableBytes))
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return getString(R.string.storage_unknown)
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "${bytes} ${units[unit]}"
        else String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }

    private fun startAndBindService() {
        val intent = Intent(this, MultiBmsService::class.java)
        startForegroundService(intent)
        if (!bound && !binding) {
            binding = bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
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
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(R.color.ui_text))
    }

    private fun matchParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun weightParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        setMargins(dp(2), 0, dp(2), 0)
    }

    private fun chooseLanguage() {
        val tags = listOf("system") + UiPreferences.languageTags(this)
        val names = listOf(getString(R.string.system_default)) + resources.getStringArray(R.array.language_names)
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setSingleChoiceItems(names.toTypedArray(), tags.indexOf(UiPreferences.language(this))) { dialog, index ->
                UiPreferences.setLanguage(this, tags[index])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun chooseTheme() {
        val choices = listOf("light", "dark")
        AlertDialog.Builder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(arrayOf(getString(R.string.light), getString(R.string.dark)),
                choices.indexOf(UiPreferences.theme(this))) { dialog, index ->
                UiPreferences.setTheme(this, choices[index])
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    @Suppress("DEPRECATION")
    private fun sp(id: Int) = resources.getDimension(id) / resources.displayMetrics.scaledDensity

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun clock(timestamp: Long): String = TIME_FORMAT.format(Date(timestamp))

    companion object {
        private const val PERMISSION_REQUEST = 71
        private const val DISPLAY_SLOTS = 6
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }
}
