package com.zayants.bmsmultiprobe.model

data class ProbeDevice(
    val name: String,
    val address: String,
    val rssi: Int = Int.MIN_VALUE,
)

data class ProbeTelemetry(
    val timestamp: Long = System.currentTimeMillis(),
    val packVoltageV: Float,
    val currentA: Float,
    val socPercent: Int,
    val temperatureC: Float,
    val cellsV: List<Float>,
    /** Every valid internal temperature reading in the order exposed by the JK frame. */
    val temperaturesC: List<Float> = emptyList(),
    /** Current protection/alarm causes published by the JK runtime frame. */
    val alarms: List<String> = emptyList(),
    /** Runtime balancing status published by the JK runtime frame. */
    val balancingState: String = "unavailable",
)

data class ProbeSessionState(
    val device: ProbeDevice,
    val status: String,
    val transportConnected: Boolean = false,
    val packetCount: Long = 0,
    val lastPacketAt: Long? = null,
    val sampledAt: Long? = null,
    val telemetry: ProbeTelemetry? = null,
    val gattStatus: Int? = null,
)

data class ProbeWindow(
    val sampledAt: Long,
    val sessions: List<ProbeSessionState>,
    val packetSkewMs: Long?,
)
