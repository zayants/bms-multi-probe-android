package com.zayants.bmsmultiprobe.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.zayants.bmsmultiprobe.model.ProbeDevice
import com.zayants.bmsmultiprobe.model.ProbeSessionState
import java.util.UUID

class MultiBmsBleManager(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onScanChanged(devices: List<ProbeDevice>, scanning: Boolean)
        fun onSessionChanged(state: ProbeSessionState)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = bluetoothManager?.adapter
    private val found = linkedMapOf<String, ProbeDevice>()
    private val sessions = linkedMapOf<String, Session>()
    private var scanning = false

    private val scanTimeout = Runnable { stopScan() }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                val address = result.device.address
                val advertised = result.scanRecord?.deviceName.orEmpty().trim()
                val cached = runCatching { result.device.name.orEmpty() }.getOrDefault("").trim()
                val previous = found[address]
                val name = listOf(advertised, cached, previous?.name.orEmpty())
                    .filter { it.isNotBlank() }
                    .maxByOrNull { it.length }
                    ?: "BLE ${address.takeLast(5)}"
                found[address] = ProbeDevice(name, address, maxOf(result.rssi, previous?.rssi ?: Int.MIN_VALUE))
                publishScan()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                handler.removeCallbacks(scanTimeout)
                publishScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (sessions.isNotEmpty()) {
            publishScan()
            return
        }
        stopScan()
        val bluetooth = adapter ?: return
        if (!bluetooth.isEnabled) return
        found.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0L)
            .build()
        scanning = true
        bluetooth.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        handler.postDelayed(scanTimeout, SCAN_DURATION_MS)
        publishScan()
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (scanning) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        scanning = false
        publishScan()
    }

    @SuppressLint("MissingPermission")
    fun connect(devices: List<ProbeDevice>) {
        require(devices.size in 1..MAX_SESSIONS)
        stopScan()
        val desired = devices.associateBy { it.address }
        sessions.keys.filterNot(desired::containsKey).toList().forEach { address ->
            sessions.remove(address)?.close()
        }
        devices.forEach { device ->
            sessions.getOrPut(device.address) { Session(device) }.connect()
        }
    }

    fun currentStates(): List<ProbeSessionState> = sessions.values.map(Session::state)

    fun scannedDevices(): List<ProbeDevice> = found.values.sortedByDescending { it.rssi }

    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        stopScan()
        sessions.values.forEach(Session::close)
        sessions.clear()
    }

    private fun publishScan() {
        listener.onScanChanged(scannedDevices(), scanning)
    }

    private inner class Session(private val device: ProbeDevice) {
        private var gatt: BluetoothGatt? = null
        private var characteristic: BluetoothGattCharacteristic? = null
        private var transportConnected = false
        private var handshakeStarted = false
        private var status = "waiting"
        private var gattStatus: Int? = null
        private var frameBuffer = ByteArray(0)
        private var vendorId = ""
        private var packetCount = 0L
        private var lastPacketAt: Long? = null
        private var telemetry: com.zayants.bmsmultiprobe.model.ProbeTelemetry? = null
        private var reconnectAttempt = 0
        private var closed = false

        private val setupRetry = object : Runnable {
            @SuppressLint("MissingPermission")
            override fun run() {
                val activeGatt = gatt ?: return
                if (closed || !transportConnected || handshakeStarted) return
                status = "retrying telemetry setup"
                emit()
                val data = activeGatt
                    .getService(UUID.fromString(JkReadOnlyProtocol.SERVICE_UUID))
                    ?.getCharacteristic(UUID.fromString(JkReadOnlyProtocol.CHARACTERISTIC_UUID))
                if (data == null) {
                    activeGatt.discoverServices()
                } else {
                    enableNotifications(activeGatt, data)
                }
                handler.postDelayed(this, SETUP_RETRY_MS)
            }
        }

        private val reconnect = object : Runnable {
            @SuppressLint("MissingPermission")
            override fun run() {
                val activeGatt = gatt ?: return
                if (closed || transportConnected) return
                status = "reconnecting same GATT"
                emit()
                activeGatt.connect()
                reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(MAX_RECONNECT_ATTEMPT)
                handler.postDelayed(this, reconnectDelayMs(reconnectAttempt))
            }
        }

        private val callback = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(activeGatt: BluetoothGatt, result: Int, newState: Int) {
                if (gatt !== activeGatt || closed) {
                    activeGatt.close()
                    return
                }
                handler.post {
                    if (gatt !== activeGatt || closed) return@post
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        transportConnected = true
                        reconnectAttempt = 0
                        gattStatus = null
                        status = "connected; discovering"
                        handler.removeCallbacks(reconnect)
                        handler.removeCallbacks(setupRetry)
                        handler.postDelayed(setupRetry, SETUP_RETRY_MS)
                        emit()
                        if (!activeGatt.requestMtu(517)) activeGatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        transportConnected = false
                        handshakeStarted = false
                        characteristic = null
                        frameBuffer = ByteArray(0)
                        gattStatus = result
                        status = "disconnected; keeping GATT"
                        handler.removeCallbacks(setupRetry)
                        handler.removeCallbacks(reconnect)
                        handler.postDelayed(reconnect, SAME_GATT_RETRY_MS)
                        emit()
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(activeGatt: BluetoothGatt, mtu: Int, result: Int) {
                if (gatt !== activeGatt || closed) return
                handler.post {
                    if (gatt === activeGatt && !closed) {
                        status = "MTU $mtu; discovering"
                        emit()
                        activeGatt.discoverServices()
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(activeGatt: BluetoothGatt, result: Int) {
                if (gatt !== activeGatt || closed) return
                handler.post {
                    if (gatt !== activeGatt || closed) return@post
                    if (result != BluetoothGatt.GATT_SUCCESS) {
                        status = "service discovery error $result"
                        emit()
                        return@post
                    }
                    val data = activeGatt
                        .getService(UUID.fromString(JkReadOnlyProtocol.SERVICE_UUID))
                        ?.getCharacteristic(UUID.fromString(JkReadOnlyProtocol.CHARACTERISTIC_UUID))
                    if (data == null) {
                        status = "FFE1 not found"
                        emit()
                    } else {
                        enableNotifications(activeGatt, data)
                    }
                }
            }

            override fun onDescriptorWrite(
                activeGatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                result: Int,
            ) {
                if (gatt !== activeGatt || closed) return
                handler.post {
                    if (gatt !== activeGatt || closed) return@post
                    if (result != BluetoothGatt.GATT_SUCCESS) {
                        status = "notification error $result"
                        emit()
                        return@post
                    }
                    startReadOnlyHandshake(activeGatt)
                }
            }

            @Deprecated("Deprecated in Android 13")
            override fun onCharacteristicChanged(
                activeGatt: BluetoothGatt,
                changed: BluetoothGattCharacteristic,
            ) {
                if (gatt === activeGatt && !closed) {
                    @Suppress("DEPRECATION")
                    val value = changed.value?.copyOf()
                    value?.let { chunk ->
                        handler.post { if (gatt === activeGatt && !closed) consume(chunk) }
                    }
                }
            }

            override fun onCharacteristicChanged(
                activeGatt: BluetoothGatt,
                changed: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (gatt === activeGatt && !closed) {
                    val copy = value.copyOf()
                    handler.post { if (gatt === activeGatt && !closed) consume(copy) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        fun connect() {
            if (closed) return
            if (gatt != null) {
                if (!transportConnected) gatt?.connect()
                return
            }
            val bluetooth = adapter
            if (bluetooth == null || !bluetooth.isEnabled) {
                status = "Bluetooth unavailable"
                emit()
                return
            }
            val remote = runCatching { bluetooth.getRemoteDevice(device.address) }.getOrNull()
            if (remote == null) {
                status = "invalid address"
                emit()
                return
            }
            status = "connecting"
            emit()
            gatt = remote.connectGatt(
                context,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
            )
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(activeGatt: BluetoothGatt, data: BluetoothGattCharacteristic) {
            if (gatt !== activeGatt || closed || handshakeStarted) return
            characteristic = data
            activeGatt.setCharacteristicNotification(data, true)
            val descriptor = data.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor == null) {
                status = "notification descriptor missing"
                emit()
                return
            }
            status = "enabling notifications"
            emit()
            val started = if (Build.VERSION.SDK_INT >= 33) {
                activeGatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    activeGatt.writeDescriptor(descriptor)
                }
            }
            if (!started) {
                status = "Bluetooth busy; setup will retry"
                emit()
            }
        }

        private fun startReadOnlyHandshake(activeGatt: BluetoothGatt) {
            if (gatt !== activeGatt || closed || handshakeStarted) return
            handshakeStarted = true
            handler.removeCallbacks(setupRetry)
            status = "requesting device info"
            emit()
            writeReadRequest(JkReadOnlyProtocol.DEVICE_INFO_REGISTER)
            handler.postDelayed({
                if (gatt === activeGatt && !closed) {
                    status = "requesting telemetry"
                    emit()
                    writeReadRequest(JkReadOnlyProtocol.TELEMETRY_REGISTER)
                }
            }, TELEMETRY_START_DELAY_MS)
        }

        @SuppressLint("MissingPermission")
        private fun writeReadRequest(register: Int): Boolean {
            val activeGatt = gatt ?: return false
            val data = characteristic ?: return false
            val command = JkReadOnlyProtocol.buildReadRequest(register)
            return if (Build.VERSION.SDK_INT >= 33) {
                activeGatt.writeCharacteristic(
                    data,
                    command,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    data.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    data.value = command
                    activeGatt.writeCharacteristic(data)
                }
            }
        }

        private fun consume(chunk: ByteArray) {
            frameBuffer += chunk
            while (true) {
                val start = JkReadOnlyProtocol.frameStart(frameBuffer)
                if (start < 0) {
                    frameBuffer = frameBuffer.takeLast(3).toByteArray()
                    return
                }
                if (start > 0) frameBuffer = frameBuffer.copyOfRange(start, frameBuffer.size)
                if (frameBuffer.size < JkReadOnlyProtocol.FRAME_SIZE) return
                val frame = frameBuffer.copyOfRange(0, JkReadOnlyProtocol.FRAME_SIZE)
                if (!JkReadOnlyProtocol.checksumIsValid(frame)) {
                    frameBuffer = frameBuffer.copyOfRange(1, frameBuffer.size)
                    continue
                }
                frameBuffer = frameBuffer.copyOfRange(JkReadOnlyProtocol.FRAME_SIZE, frameBuffer.size)
                when (JkReadOnlyProtocol.frameType(frame)) {
                    0x03 -> vendorId = JkReadOnlyProtocol.readVendorId(frame)
                    0x02 -> JkReadOnlyProtocol.parseTelemetry(frame, vendorId)?.let { sample ->
                        telemetry = sample
                        lastPacketAt = sample.timestamp
                        packetCount += 1
                        status = "telemetry"
                        emit()
                    }
                }
            }
        }

        fun state() = ProbeSessionState(
            device = device,
            status = status,
            transportConnected = transportConnected,
            packetCount = packetCount,
            lastPacketAt = lastPacketAt,
            telemetry = telemetry,
            gattStatus = gattStatus,
        )

        private fun emit() {
            val snapshot = state()
            handler.post { if (!closed) listener.onSessionChanged(snapshot) }
        }

        @SuppressLint("MissingPermission")
        fun close() {
            if (closed) return
            closed = true
            handler.removeCallbacks(setupRetry)
            handler.removeCallbacks(reconnect)
            gatt?.disconnect()
            gatt?.close()
            gatt = null
            characteristic = null
        }
    }

    companion object {
        const val MAX_SESSIONS = 4
        private const val SCAN_DURATION_MS = 25_000L
        private const val SETUP_RETRY_MS = 5_000L
        private const val TELEMETRY_START_DELAY_MS = 1_000L
        private const val SAME_GATT_RETRY_MS = 3_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val MAX_RECONNECT_ATTEMPT = 5
        private val CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private fun reconnectDelayMs(attempt: Int): Long =
            (SAME_GATT_RETRY_MS * (1L shl attempt.coerceIn(0, MAX_RECONNECT_ATTEMPT)))
                .coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }
}
