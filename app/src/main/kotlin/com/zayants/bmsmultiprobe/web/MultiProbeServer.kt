package com.zayants.bmsmultiprobe.web

import android.content.Context
import com.zayants.bmsmultiprobe.SampleWindow
import com.zayants.bmsmultiprobe.model.ProbeWindow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MultiProbeServer(
    private val context: Context,
    private val windowProvider: () -> ProbeWindow,
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService = newWorkerPool()

    fun start(): Boolean {
        if (serverSocket?.isClosed == false) return true
        if (workers.isShutdown) workers = newWorkerPool()
        return runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                serverSocket = this
            }.also { server ->
                acceptThread = Thread({ acceptLoop(server) }, "multi-probe-http").apply {
                    isDaemon = true
                    start()
                }
            }
        }.isSuccess
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        workers.shutdownNow()
    }

    fun primaryUrl(): String? = localIpv4Addresses().firstOrNull()?.let { "http://$it:$PORT" }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { workers.execute { handleSafely(client) } }
                .onFailure { runCatching { client.close() } }
        }
    }

    private fun handleSafely(client: Socket) {
        client.use { runCatching { handle(it) } }
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
        val requestLine = reader.readLine().orEmpty()
        var headers = 0
        while (headers++ < MAX_HEADERS) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')
        if (method == "OPTIONS") {
            respond(client, 204, "text/plain", "")
            return
        }
        if (method != "GET" && method != "HEAD") {
            respond(client, 405, "text/plain; charset=utf-8", "Method not allowed")
            return
        }
        val head = method == "HEAD"
        when (path) {
            "/" -> respond(client, 200, "text/html; charset=utf-8", if (head) "" else browserPage())
            "/health" -> respond(client, 200, "text/plain; charset=utf-8", if (head) "" else "ok")
            "/api/v1/multi/snapshot" -> respond(
                client,
                200,
                "application/json; charset=utf-8",
                if (head) "" else snapshotJson(),
            )
            else -> respond(client, 404, "text/plain; charset=utf-8", if (head) "" else "Not found")
        }
    }

    private fun snapshotJson(): String {
        val now = System.currentTimeMillis()
        val window = windowProvider()
        return JSONObject().apply {
            put("apiVersion", 1)
            put("mode", "multi-probe")
            put("serverTime", now)
            put("sampledAt", window.sampledAt)
            put("sampleIntervalMs", SampleWindow.INTERVAL_MS)
            put("packetSkewMs", window.packetSkewMs ?: JSONObject.NULL)
            put("sessionCount", window.sessions.size)
            put("freshCount", window.sessions.count { it.telemetry != null })
            put("sessions", JSONArray().apply {
                window.sessions.forEachIndexed { index, state ->
                    val sample = state.telemetry
                    put(JSONObject().apply {
                        put("index", index)
                        put("name", state.device.name)
                        put("address", state.device.address)
                        put("status", state.status)
                        put("connected", state.transportConnected)
                        put("packetCount", state.packetCount)
                        put("lastPacketAt", state.lastPacketAt ?: JSONObject.NULL)
                        put("stale", sample == null)
                        put("sampleAgeMs", sample?.let { (now - it.timestamp).coerceAtLeast(0) } ?: JSONObject.NULL)
                        put("gattStatus", state.gattStatus ?: JSONObject.NULL)
                        if (sample != null) {
                            put("timestamp", sample.timestamp)
                            put("packVoltageV", sample.packVoltageV.toDouble())
                            put("currentA", sample.currentA.toDouble())
                            put("powerW", (sample.packVoltageV * sample.currentA).toDouble())
                            put("socPercent", sample.socPercent)
                            put("temperatureC", sample.temperatureC.toDouble())
                            put("temperaturesC", JSONArray(sample.temperaturesC.map(Float::toDouble)))
                            put("cellsV", JSONArray(sample.cellsV.map(Float::toDouble)))
                            put("alarms", JSONArray(sample.alarms))
                            put("alarmCount", sample.alarms.size)
                            put("hasAlarm", sample.alarms.isNotEmpty())
                            put("balancingState", sample.balancingState)
                        }
                    })
                }
            })
        }.toString()
    }

    private fun browserPage(): String = BrowserPage.render(context)

    private fun respond(client: Socket, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            405 -> "Method Not Allowed"
            else -> "Not Found"
        }
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        client.getOutputStream().apply {
            write(header)
            write(bytes)
            flush()
        }
    }

    private fun localIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network ->
                network.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .mapNotNull { address ->
                        val value = address.hostAddress ?: return@mapNotNull null
                        val priority = when {
                            network.name.startsWith("wlan", ignoreCase = true) -> 0
                            isPrivateAddress(value) -> 1
                            else -> 2
                        }
                        priority to value
                    }
            }
            .sortedBy { it.first }
            .map { it.second }
            .distinct()
    }.getOrDefault(emptyList())

    private fun isPrivateAddress(address: String): Boolean =
        address.startsWith("10.") || address.startsWith("192.168.") ||
            address.substringBefore('.').toIntOrNull() == 172 &&
            address.substringAfter('.').substringBefore('.').toIntOrNull() in 16..31

    private fun newWorkerPool(): ExecutorService = Executors.newCachedThreadPool()

    companion object {
        const val PORT = 8766
        private const val READ_TIMEOUT_MS = 3_000
        private const val MAX_HEADERS = 50
    }
}
