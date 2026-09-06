package com.zayants.bmsmultiprobe.ble

import com.zayants.bmsmultiprobe.model.ProbeTelemetry
import java.nio.ByteBuffer
import java.nio.ByteOrder

object JkReadOnlyProtocol {
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val FRAME_SIZE = 300
    const val DEVICE_INFO_REGISTER = 0x97
    const val TELEMETRY_REGISTER = 0x96

    private val framePreamble = byteArrayOf(0x55, 0xAA.toByte(), 0xEB.toByte(), 0x90.toByte())
    private val requestPreamble = byteArrayOf(0xAA.toByte(), 0x55, 0x90.toByte(), 0xEB.toByte())

    /** JK FFE1 is bidirectional; this frame only requests a read-only data register. */
    fun buildReadRequest(register: Int): ByteArray {
        require(register == DEVICE_INFO_REGISTER || register == TELEMETRY_REGISTER)
        val frame = ByteArray(20)
        requestPreamble.copyInto(frame)
        frame[4] = register.toByte()
        frame[19] = frame.take(19).fold(0) { sum, byte ->
            (sum + (byte.toInt() and 0xFF)) and 0xFF
        }.toByte()
        return frame
    }

    fun frameStart(bytes: ByteArray): Int {
        if (bytes.size < framePreamble.size) return -1
        for (index in 0..bytes.size - framePreamble.size) {
            if (framePreamble.indices.all { offset -> bytes[index + offset] == framePreamble[offset] }) {
                return index
            }
        }
        return -1
    }

    fun checksumIsValid(frame: ByteArray): Boolean {
        if (frame.size != FRAME_SIZE) return false
        val expected = frame.dropLast(1).fold(0) { sum, byte ->
            (sum + (byte.toInt() and 0xFF)) and 0xFF
        }
        return expected == frame.last().u8()
    }

    fun frameType(frame: ByteArray): Int = frame.getOrNull(4)?.u8() ?: -1

    fun readVendorId(frame: ByteArray): String = frame
        .copyOfRange(6, 22.coerceAtMost(frame.size))
        .takeWhile { it != 0.toByte() }
        .toByteArray()
        .toString(Charsets.US_ASCII)
        .trim()

    fun parseTelemetry(frame: ByteArray, vendorId: String = ""): ProbeTelemetry? {
        if (frameType(frame) != 0x02) return null
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val first = buffer.getFloat(6)
        val second = buffer.getFloat(10)
        if (plausibleCell(first) && plausibleCell(second)) return parseJk04(frame)

        val cells32 = jk02Cells(frame, 32)
        val cells24 = jk02Cells(frame, 24)
        val layout32 = cells32?.takeIf { plausibleLayout(frame, 32, it.sum()) }
        val layout24 = cells24?.takeIf { plausibleLayout(frame, 0, it.sum()) }
        val normalizedVendor = vendorId.uppercase().replace('-', '_')
        val prefer32 = normalizedVendor.contains("_PB") ||
            normalizedVendor.startsWith("JK_BD") ||
            normalizedVendor.startsWith("JK_HY") ||
            normalizedVendor.startsWith("JK_B1A8S10P")

        return when {
            layout32 != null && (layout24 == null || prefer32) -> parseJk02(frame, layout32, 32)
            layout24 != null -> parseJk02(frame, layout24, 0)
            layout32 != null -> parseJk02(frame, layout32, 32)
            else -> null
        }
    }

    private fun parseJk02(frame: ByteArray, cells: List<Float>, offset: Int): ProbeTelemetry? {
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val packVoltage = buffer.getInt(118 + offset).toUInt().toLong() * 0.001f
        val current = buffer.getInt(126 + offset) * 0.001f
        val temperatures = listOf(
            buffer.getShort(130 + offset) * 0.1f,
            buffer.getShort(132 + offset) * 0.1f,
        ).filter { it in -80f..150f }
        val soc = frame[141 + offset].u8()
        if (packVoltage <= 0f || current !in -2_000f..2_000f || temperatures.isEmpty() || soc !in 0..100) {
            return null
        }
        return ProbeTelemetry(
            packVoltageV = packVoltage,
            currentA = current,
            socPercent = soc,
            temperatureC = temperatures.max(),
            cellsV = cells,
            temperaturesC = temperatures,
            alarms = decodeJk02Alarms(frame, offset),
            balancingState = decodeJk02Balancing(frame, offset),
        )
    }

    private fun parseJk04(frame: ByteArray): ProbeTelemetry? {
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val cells = buildList {
            repeat(24) { index ->
                val value = buffer.getFloat(6 + index * 4)
                if (value != 0f) {
                    if (!plausibleCell(value)) return null
                    add(value)
                }
            }
        }
        if (cells.size < 4) return null
        return ProbeTelemetry(
            packVoltageV = cells.sum(),
            currentA = 0f,
            socPercent = 0,
            temperatureC = 0f,
            cellsV = cells,
        )
    }

    private fun jk02Cells(frame: ByteArray, maximumCells: Int): List<Float>? {
        if (frame.size < 6 + maximumCells * 2) return null
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        return buildList {
            repeat(maximumCells) { index ->
                val value = buffer.getShort(6 + index * 2).toUShort().toInt() * 0.001f
                if (value != 0f) {
                    if (!plausibleCell(value)) return null
                    add(value)
                }
            }
        }.takeIf { it.size >= 3 }
    }

    private fun plausibleLayout(frame: ByteArray, offset: Int, cellSum: Float): Boolean {
        if (cellSum <= 0f || frame.size <= 141 + offset) return false
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val packVoltage = buffer.getInt(118 + offset).toUInt().toLong() * 0.001f
        val current = buffer.getInt(126 + offset) * 0.001f
        val temperature1 = buffer.getShort(130 + offset) * 0.1f
        val temperature2 = buffer.getShort(132 + offset) * 0.1f
        val soc = frame[141 + offset].u8()
        return packVoltage > 0f &&
            kotlin.math.abs(packVoltage - cellSum) <= maxOf(1f, cellSum * 0.1f) &&
            current in -2_000f..2_000f &&
            (temperature1 in -80f..150f || temperature2 in -80f..150f) &&
            soc in 0..100
    }

    private fun plausibleCell(value: Float) = value.isFinite() && value in 1f..5.5f

    private fun decodeJk02Alarms(frame: ByteArray, offset: Int): List<String> {
        val mask = if (offset == 32) {
            frame.u16Be(134 + offset)
        } else {
            frame.u16Be(136)
        }
        if (mask == 0) return emptyList()

        val labels = if (offset == 32) JK02_32S_ALARMS else JK02_24S_ALARMS
        return labels.mapIndexedNotNull { bit, label ->
            label?.takeIf { mask and (1 shl bit) != 0 }
        }
    }

    private fun decodeJk02Balancing(frame: ByteArray, offset: Int): String = when (frame[140 + offset].u8()) {
        0 -> "off"
        1 -> "charging"
        2 -> "discharging"
        else -> "unknown"
    }

    private fun ByteArray.u16Be(offset: Int): Int =
        (getOrNull(offset)?.u8() ?: 0) shl 8 or (getOrNull(offset + 1)?.u8() ?: 0)

    private fun Byte.u8() = toInt() and 0xFF

    private val JK02_24S_ALARMS = arrayOf(
        "Charge over-temperature protection",
        "Charge under-temperature protection",
        "Coprocessor communication error",
        "Cell under-voltage protection",
        "Battery pack under-voltage protection",
        "Discharge over-current protection",
        "Discharge short-circuit protection",
        "Discharge over-temperature protection",
        "Wire resistance abnormal",
        "MOSFET over-temperature protection",
        "Configured cell count does not match",
        "Current sensor anomaly",
        null, null, null, null,
    )

    private val JK02_32S_ALARMS = arrayOf(
        "Wire resistance abnormal",
        "MOSFET over-temperature protection",
        "Configured cell count does not match",
        null,
        null, // Fully charged is a state bit, not an alarm.
        "Battery pack over-voltage protection",
        "Charge over-current protection",
        "Charge short-circuit protection",
        "Charge over-temperature protection",
        "Charge under-temperature protection",
        "Coprocessor communication error",
        "Cell under-voltage protection",
        "Battery pack under-voltage protection",
        "Discharge over-current protection",
        "Discharge short-circuit protection",
        "Discharge over-temperature protection",
    )
}
