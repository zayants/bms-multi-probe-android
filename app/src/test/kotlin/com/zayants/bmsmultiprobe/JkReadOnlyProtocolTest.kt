package com.zayants.bmsmultiprobe

import com.zayants.bmsmultiprobe.ble.JkReadOnlyProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class JkReadOnlyProtocolTest {
    @Test
    fun buildsOnlyKnownReadRequests() {
        val deviceInfo = JkReadOnlyProtocol.buildReadRequest(JkReadOnlyProtocol.DEVICE_INFO_REGISTER)
        val telemetry = JkReadOnlyProtocol.buildReadRequest(JkReadOnlyProtocol.TELEMETRY_REGISTER)

        assertEquals(20, deviceInfo.size)
        assertEquals(0x97, deviceInfo[4].toInt() and 0xFF)
        assertEquals(0x96, telemetry[4].toInt() and 0xFF)
        assertEquals(checksum(deviceInfo), deviceInfo.last().toInt() and 0xFF)
        assertEquals(checksum(telemetry), telemetry.last().toInt() and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAnArbitrarySettingsRegister() {
        JkReadOnlyProtocol.buildReadRequest(0x1F)
    }

    @Test
    fun decodes24sRuntimeAlarmsFromTheReadOnlyTelemetryFrame() {
        val telemetry = JkReadOnlyProtocol.parseTelemetry(
            jk02Frame(cellCount = 8, alarmMask = 0x0070, balancingState = 1),
        )

        assertEquals(
            listOf(
                "Battery pack under-voltage protection",
                "Discharge over-current protection",
                "Discharge short-circuit protection",
            ),
            telemetry?.alarms,
        )
        assertEquals("charging", telemetry?.balancingState)
        assertEquals(listOf(25.0f, 26.0f), telemetry?.temperaturesC)
    }

    @Test
    fun decodes32sRuntimeAlarmsAndIgnoresFullyChargedState() {
        val telemetry = JkReadOnlyProtocol.parseTelemetry(
            jk02Frame(cellCount = 32, alarmMask = 0x2010, balancingState = 2),
        )

        assertEquals(
            listOf("Discharge over-current protection"),
            telemetry?.alarms,
        )
        assertEquals("discharging", telemetry?.balancingState)
    }

    private fun jk02Frame(cellCount: Int, alarmMask: Int, balancingState: Int): ByteArray {
        val offset = if (cellCount == 32) 32 else 0
        val frame = ByteArray(300)
        frame[0] = 0x55
        frame[1] = 0xAA.toByte()
        frame[2] = 0xEB.toByte()
        frame[3] = 0x90.toByte()
        frame[4] = 0x02
        repeat(cellCount) { putU16Le(frame, 6 + it * 2, 3_300) }
        putU32Le(frame, 118 + offset, cellCount * 3_300)
        putU32Le(frame, 126 + offset, 1_500)
        putU16Le(frame, 130 + offset, 250)
        putU16Le(frame, 132 + offset, 260)
        val alarmOffset = if (offset == 32) 134 + offset else 136
        frame[alarmOffset] = (alarmMask shr 8).toByte()
        frame[alarmOffset + 1] = alarmMask.toByte()
        frame[140 + offset] = balancingState.toByte()
        frame[141 + offset] = 80
        frame[299] = checksum(frame).toByte()
        return frame
    }

    private fun putU16Le(frame: ByteArray, offset: Int, value: Int) {
        frame[offset] = value.toByte()
        frame[offset + 1] = (value shr 8).toByte()
    }

    private fun putU32Le(frame: ByteArray, offset: Int, value: Int) {
        repeat(4) { byte -> frame[offset + byte] = (value shr (byte * 8)).toByte() }
    }

    private fun checksum(frame: ByteArray): Int = frame.dropLast(1)
        .sumOf { it.toInt() and 0xFF } and 0xFF
}
