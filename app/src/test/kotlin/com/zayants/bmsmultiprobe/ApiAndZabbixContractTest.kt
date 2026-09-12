package com.zayants.bmsmultiprobe

import com.zayants.bmsmultiprobe.model.ProbeDevice
import com.zayants.bmsmultiprobe.model.ProbeSessionState
import com.zayants.bmsmultiprobe.model.ProbeTelemetry
import com.zayants.bmsmultiprobe.model.ProbeWindow
import com.zayants.bmsmultiprobe.web.MultiProbeServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ApiAndZabbixContractTest {
    @Test fun booleanFieldsKeepTheirTypeAndExposeNumericAliases() {
        val now = System.currentTimeMillis()
        val live = ProbeSessionState(
            ProbeDevice("Battery one", "AA:BB:CC:DD:EE:01"), "telemetry", true, 4, now,
            telemetry = ProbeTelemetry(now, 26.2f, 1.5f, 73, 24f,
                listOf(3.27f, 3.28f), alarms = listOf("test alarm")),
        )
        val stale = ProbeSessionState(
            ProbeDevice("Battery two", "AA:BB:CC:DD:EE:02"), "reconnecting", false,
        )
        val server = MultiProbeServer(RuntimeEnvironment.getApplication()) {
            ProbeWindow(now, listOf(live, stale), null)
        }
        val sessions = JSONObject(server.snapshotJson()).getJSONArray("sessions")
        val first = sessions.getJSONObject(0)
        assertTrue(first.get("connected") is Boolean)
        assertEquals(1, first.getInt("connectedValue"))
        assertFalse(first.getBoolean("stale"))
        assertEquals(0, first.getInt("staleValue"))
        assertTrue(first.getBoolean("hasAlarm"))
        assertEquals(1, first.getInt("hasAlarmValue"))
        val second = sessions.getJSONObject(1)
        assertEquals(0, second.getInt("connectedValue"))
        assertEquals(1, second.getInt("staleValue"))
    }

    @Test fun zabbixDiscoveryUsesDuktapeCompatibleSyntaxAndNumericFields() {
        val template = File("../zabbix/bms-multi-probe-zabbix-7.yaml")
            .takeIf(File::isFile) ?: File("zabbix/bms-multi-probe-zabbix-7.yaml")
        val text = template.readText()
        assertFalse(Regex("\\b(?:let|const)\\b").containsMatchIn(text))
        assertFalse(Regex("for\\s*\\([^)]*\\bof\\b").containsMatchIn(text))
        assertFalse(text.contains('`'))
        assertFalse(text.contains("BOOL_TO_DECIMAL"))
        assertFalse(text.contains("name: BMS Multi Probe: Raw snapshot"))
        assertTrue(text.contains(".staleValue"))
        assertTrue(text.contains(".connectedValue"))
    }
}
