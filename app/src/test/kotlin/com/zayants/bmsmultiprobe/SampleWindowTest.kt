package com.zayants.bmsmultiprobe

import com.zayants.bmsmultiprobe.model.ProbeDevice
import com.zayants.bmsmultiprobe.model.ProbeSessionState
import com.zayants.bmsmultiprobe.model.ProbeTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SampleWindowTest {
    @Test
    fun calculatesSkewAcrossThreeLatestPackets() {
        val window = SampleWindow.create(
            now = 20_000,
            sessions = listOf(state("A", 18_000), state("B", 19_100), state("C", 17_700)),
        )

        assertEquals(1_400L, window.packetSkewMs)
        assertEquals(3, window.sessions.count { it.telemetry != null })
    }

    @Test
    fun dropsStaleTelemetryFromFiveSecondWindow() {
        val window = SampleWindow.create(
            now = 30_001,
            sessions = listOf(state("A", 15_000), state("B", 29_000)),
        )

        assertNull(window.sessions.first().telemetry)
        assertEquals(29_000L, window.sessions.last().telemetry?.timestamp)
        assertNull(window.packetSkewMs)
    }

    private fun state(address: String, timestamp: Long) = ProbeSessionState(
        device = ProbeDevice(address, address),
        status = "telemetry",
        telemetry = ProbeTelemetry(timestamp, 26.4f, 1f, 80, 25f, List(8) { 3.3f }),
    )
}
