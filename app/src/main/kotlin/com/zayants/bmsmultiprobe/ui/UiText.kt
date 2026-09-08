package com.zayants.bmsmultiprobe.ui

import android.content.Context
import com.zayants.bmsmultiprobe.R

/** Translate at the presentation boundary only; protocol/API values remain stable. */
object UiText {
    val statuses = mapOf(
        "waiting" to R.string.status_waiting,
        "telemetry" to R.string.status_live,
        "connecting" to R.string.status_connecting,
        "connected; discovering" to R.string.status_discovering,
        "reconnecting same GATT" to R.string.status_reconnecting,
        "disconnected; keeping GATT" to R.string.status_disconnected,
        "retrying telemetry setup" to R.string.status_retry,
        "FFE1 not found" to R.string.status_ffe_missing,
        "Bluetooth unavailable" to R.string.status_bluetooth_unavailable,
        "invalid address" to R.string.status_invalid_address,
        "notification descriptor missing" to R.string.status_descriptor_missing,
        "enabling notifications" to R.string.status_notifications,
        "Bluetooth busy; setup will retry" to R.string.status_busy,
        "requesting device info" to R.string.status_device_info,
        "requesting telemetry" to R.string.status_telemetry,
    )
    val alarms = mapOf(
        "Charge over-temperature protection" to R.string.alarm_0,
        "Charge under-temperature protection" to R.string.alarm_1,
        "Coprocessor communication error" to R.string.alarm_2,
        "Cell under-voltage protection" to R.string.alarm_3,
        "Battery pack under-voltage protection" to R.string.alarm_4,
        "Discharge over-current protection" to R.string.alarm_5,
        "Discharge short-circuit protection" to R.string.alarm_6,
        "Discharge over-temperature protection" to R.string.alarm_7,
        "Wire resistance abnormal" to R.string.alarm_8,
        "MOSFET over-temperature protection" to R.string.alarm_9,
        "Configured cell count does not match" to R.string.alarm_10,
        "Current sensor anomaly" to R.string.alarm_11,
        "Battery pack over-voltage protection" to R.string.alarm_12,
        "Charge over-current protection" to R.string.alarm_13,
        "Charge short-circuit protection" to R.string.alarm_14,
    )

    data class StatusText(val resource: Int, val argument: String? = null)

    fun statusText(value: String): StatusText {
        statuses[value]?.let { return StatusText(it) }
        Regex("^MTU (\\d+); discovering$").matchEntire(value)?.let {
            return StatusText(R.string.status_mtu, it.groupValues[1])
        }
        Regex("^service discovery error (-?\\d+)$").matchEntire(value)?.let {
            return StatusText(R.string.status_service_error, it.groupValues[1])
        }
        Regex("^notification error (-?\\d+)$").matchEntire(value)?.let {
            return StatusText(R.string.status_notification_error, it.groupValues[1])
        }
        return StatusText(R.string.status_unknown)
    }

    fun status(context: Context, value: String): String = statusText(value).let {
        if (it.argument == null) context.getString(it.resource)
        else context.getString(it.resource, it.argument)
    }

    // Keep an unrecognised future alarm readable rather than hiding its diagnostic information.
    fun alarm(context: Context, value: String): String = alarms[value]?.let(context::getString) ?: value
}
