package com.zayants.bmsmultiprobe

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.zayants.bmsmultiprobe.ble.MultiBmsBleManager
import com.zayants.bmsmultiprobe.model.ProbeDevice
import com.zayants.bmsmultiprobe.model.ProbeSessionState
import com.zayants.bmsmultiprobe.model.ProbeWindow
import com.zayants.bmsmultiprobe.web.MultiProbeServer
import java.util.concurrent.CopyOnWriteArraySet

class MultiBmsService : Service(), MultiBmsBleManager.Listener {
    interface Observer {
        fun onScanChanged(devices: List<ProbeDevice>, scanning: Boolean) = Unit
        fun onSessionsChanged(sessions: List<ProbeSessionState>) = Unit
        fun onWindow(window: ProbeWindow) = Unit
    }

    inner class LocalBinder : Binder() {
        fun service(): MultiBmsService = this@MultiBmsService
    }

    private val binder = LocalBinder()
    private val observers = CopyOnWriteArraySet<Observer>()
    private val handler = Handler(Looper.getMainLooper())
    private val states = linkedMapOf<String, ProbeSessionState>()
    private val preferences by lazy { getSharedPreferences(PREFERENCES, MODE_PRIVATE) }
    private lateinit var manager: MultiBmsBleManager
    private lateinit var webServer: MultiProbeServer
    @Volatile
    private var lastWindow = ProbeWindow(System.currentTimeMillis(), emptyList(), null)

    private val sampleTicker = object : Runnable {
        override fun run() {
            lastWindow = SampleWindow.create(System.currentTimeMillis(), manager.currentStates())
            observers.forEach { it.onWindow(lastWindow) }
            handler.postDelayed(this, SampleWindow.INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Waiting for up to 4 BMS connections"))
        manager = MultiBmsBleManager(this, this)
        webServer = MultiProbeServer { lastWindow }
        webServer.start()
        handler.post(sampleTicker)
        if (hasBluetoothPermission()) {
            loadDevices().takeIf { it.isNotEmpty() }?.let(manager::connect)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        handler.removeCallbacks(sampleTicker)
        webServer.stop()
        manager.disconnectAll()
        super.onDestroy()
    }

    fun addObserver(observer: Observer) {
        observers += observer
        observer.onScanChanged(manager.scannedDevices(), false)
        observer.onSessionsChanged(manager.currentStates())
        observer.onWindow(lastWindow)
    }

    fun removeObserver(observer: Observer) {
        observers -= observer
    }

    fun scan() = manager.scan()

    fun gatewayUrl(): String? = webServer.primaryUrl()

    fun connect(devices: List<ProbeDevice>) {
        require(devices.size in 1..MultiBmsBleManager.MAX_SESSIONS)
        preferences.edit().putStringSet(
            KEY_DEVICES,
            devices.map { "${it.address}\t${it.name}" }.toSet(),
        ).apply()
        manager.connect(devices)
    }

    fun disconnectAll(forget: Boolean = true) {
        manager.disconnectAll()
        states.clear()
        if (forget) preferences.edit().remove(KEY_DEVICES).apply()
        publishSessions()
    }

    override fun onScanChanged(devices: List<ProbeDevice>, scanning: Boolean) {
        observers.forEach { it.onScanChanged(devices, scanning) }
    }

    override fun onSessionChanged(state: ProbeSessionState) {
        states[state.device.address] = state
        publishSessions()
    }

    private fun publishSessions() {
        val current = manager.currentStates()
        observers.forEach { it.onSessionsChanged(current) }
        val connected = current.count { it.transportConnected }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification("$connected/${current.size} BMS connected"))
    }

    private fun loadDevices(): List<ProbeDevice> = preferences
        .getStringSet(KEY_DEVICES, emptySet())
        .orEmpty()
        .mapNotNull { value ->
            val address = value.substringBefore('\t').trim()
            val name = value.substringAfter('\t', "JK BMS").trim()
            address.takeIf { it.isNotBlank() }?.let { ProbeDevice(name.ifBlank { "JK BMS" }, it) }
        }
        .take(MultiBmsBleManager.MAX_SESSIONS)

    private fun hasBluetoothPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "BMS Multi Probe",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("BMS Multi Probe 0.3.7")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setOngoing(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "bms_multi_probe"
        private const val NOTIFICATION_ID = 4101
        private const val PREFERENCES = "multi_probe"
        private const val KEY_DEVICES = "selected_devices"
    }
}
