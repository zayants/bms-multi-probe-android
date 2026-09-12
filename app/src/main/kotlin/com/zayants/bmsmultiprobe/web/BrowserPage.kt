package com.zayants.bmsmultiprobe.web

import android.content.Context
import com.zayants.bmsmultiprobe.R
import com.zayants.bmsmultiprobe.ui.UiPreferences
import com.zayants.bmsmultiprobe.ui.UiText
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** HTML layout is an asset; text and palette come from the same resources as the native UI. */
object BrowserPage {
    private val strings = mapOf(
        "chart_summary" to R.string.chart_summary,
        "chart_open" to R.string.chart_open,
        "chart_gestures" to R.string.chart_gestures,
        "chart_empty_short" to R.string.chart_empty_short,
        "chart_empty" to R.string.chart_empty,
        "chart_raw" to R.string.chart_raw,
        "chart_minutes" to R.string.chart_minutes,
        "chart_read_error" to R.string.chart_read_error,
        "chart_record_error" to R.string.chart_record_error,
        "chart_hour" to R.string.chart_hour,
        "chart_day" to R.string.chart_day,
        "chart_week" to R.string.chart_week,
        "chart_month" to R.string.chart_month,
        "chart_six_months" to R.string.chart_six_months,
        "chart_now" to R.string.chart_now,
        "chart_zoom_in" to R.string.chart_zoom_in,
        "chart_zoom_out" to R.string.chart_zoom_out,
        "chart_earlier" to R.string.chart_earlier,
        "chart_later" to R.string.chart_later,
        "chart_cell" to R.string.chart_cell,
        "chart_volts" to R.string.chart_volts,
        "chart_alarm_hint" to R.string.chart_alarm_hint,
        "app_name" to R.string.app_name,
        "permissions_required" to R.string.permissions_required,
        "ble_access" to R.string.ble_access,
        "permission_help_title" to R.string.permission_help_title,
        "permission_help_message" to R.string.permission_help_message,
        "scan_help_title" to R.string.scan_help_title,
        "scan_help_message" to R.string.scan_help_message,
        "bluetooth_off_title" to R.string.bluetooth_off_title,
        "bluetooth_off_message" to R.string.bluetooth_off_message,
        "location_off_title" to R.string.location_off_title,
        "location_off_message" to R.string.location_off_message,
        "open_app_settings" to R.string.open_app_settings,
        "open_location_settings" to R.string.open_location_settings,
        "open_bluetooth_settings" to R.string.open_bluetooth_settings,
        "scan_anyway" to R.string.scan_anyway,
        "access_ready" to R.string.access_ready,
        "access_missing" to R.string.access_missing,
        "access_bluetooth_off" to R.string.access_bluetooth_off,
        "access_location_off" to R.string.access_location_off,
        "history_storage" to R.string.history_storage,
        "storage_unknown" to R.string.storage_unknown,
        "scan_hint" to R.string.scan_hint,
        "scanning" to R.string.scanning,
        "found_selected" to R.string.found_selected,
        "select_first" to R.string.select_first,
        "max_devices" to R.string.max_devices,
        "wifi_unavailable" to R.string.wifi_unavailable,
        "window_summary" to R.string.window_summary,
        "setup_intro" to R.string.setup_intro,
        "scan" to R.string.scan,
        "connect" to R.string.connect,
        "connect_count" to R.string.connect_count,
        "disconnect" to R.string.disconnect,
        "back" to R.string.back,
        "fleet" to R.string.fleet,
        "monitors" to R.string.monitors,
        "setup" to R.string.setup,
        "waiting_sample" to R.string.waiting_sample,
        "selected" to R.string.selected,
        "device_selected" to R.string.device_selected,
        "device_row" to R.string.device_row,
        "alarm_accessibility" to R.string.alarm_accessibility,
        "bms_title" to R.string.bms_title,
        "bms_slot" to R.string.bms_slot,
        "alarm" to R.string.alarm,
        "voltage" to R.string.voltage,
        "current" to R.string.current,
        "temperature" to R.string.temperature,
        "power" to R.string.power,
        "voltage_value" to R.string.voltage_value,
        "current_value" to R.string.current_value,
        "power_value" to R.string.power_value,
        "temperature_value" to R.string.temperature_value,
        "ms_value" to R.string.ms_value,
        "alarm_hint" to R.string.alarm_hint,
        "packet_summary" to R.string.packet_summary,
        "waiting_telemetry" to R.string.waiting_telemetry,
        "ready" to R.string.ready,
        "slot_available" to R.string.slot_available,
        "alarm_title" to R.string.alarm_title,
        "alarm_source" to R.string.alarm_source,
        "close" to R.string.close,
        "cancel" to R.string.cancel,
        "language" to R.string.language,
        "theme" to R.string.theme,
        "system_default" to R.string.system_default,
        "light" to R.string.light,
        "dark" to R.string.dark,
        "appearance" to R.string.appearance,
        "service_waiting" to R.string.service_waiting,
        "service_connected" to R.string.service_connected,
        "service_channel" to R.string.service_channel,
        "status_waiting" to R.string.status_waiting,
        "status_live" to R.string.status_live,
        "status_connecting" to R.string.status_connecting,
        "status_discovering" to R.string.status_discovering,
        "status_reconnecting" to R.string.status_reconnecting,
        "status_disconnected" to R.string.status_disconnected,
        "status_retry" to R.string.status_retry,
        "status_mtu" to R.string.status_mtu,
        "status_service_error" to R.string.status_service_error,
        "status_ffe_missing" to R.string.status_ffe_missing,
        "status_notification_error" to R.string.status_notification_error,
        "status_bluetooth_unavailable" to R.string.status_bluetooth_unavailable,
        "status_invalid_address" to R.string.status_invalid_address,
        "status_descriptor_missing" to R.string.status_descriptor_missing,
        "status_notifications" to R.string.status_notifications,
        "status_busy" to R.string.status_busy,
        "status_device_info" to R.string.status_device_info,
        "status_telemetry" to R.string.status_telemetry,
        "status_unknown" to R.string.status_unknown,
        "web_subtitle" to R.string.web_subtitle,
        "loading" to R.string.loading,
        "endpoint" to R.string.endpoint,
        "fresh" to R.string.fresh,
        "sampled" to R.string.sampled,
        "soc" to R.string.soc,
        "balancing" to R.string.balancing,
        "freshness" to R.string.freshness,
        "stale" to R.string.stale,
        "no_sessions" to R.string.no_sessions,
        "load_failed" to R.string.load_failed,
        "cell" to R.string.cell,
        "balance_off" to R.string.balance_off,
        "balance_charging" to R.string.balance_charging,
        "balance_discharging" to R.string.balance_discharging,
        "balance_unknown" to R.string.balance_unknown,
        "balance_unavailable" to R.string.balance_unavailable,
        "alarm_0" to R.string.alarm_0,
        "alarm_1" to R.string.alarm_1,
        "alarm_2" to R.string.alarm_2,
        "alarm_3" to R.string.alarm_3,
        "alarm_4" to R.string.alarm_4,
        "alarm_5" to R.string.alarm_5,
        "alarm_6" to R.string.alarm_6,
        "alarm_7" to R.string.alarm_7,
        "alarm_8" to R.string.alarm_8,
        "alarm_9" to R.string.alarm_9,
        "alarm_10" to R.string.alarm_10,
        "alarm_11" to R.string.alarm_11,
        "alarm_12" to R.string.alarm_12,
        "alarm_13" to R.string.alarm_13,
        "alarm_14" to R.string.alarm_14,
    )
    private val colors = mapOf(
        "background" to R.color.ui_background,
        "surface" to R.color.ui_surface,
        "text" to R.color.ui_text,
        "muted" to R.color.ui_muted,
        "border" to R.color.ui_border,
        "accent" to R.color.ui_accent,
        "success" to R.color.ui_success,
        "warning" to R.color.ui_warning,
        "alarm" to R.color.ui_alarm,
        "badge_text" to R.color.ui_badge_text,
        "selected" to R.color.ui_selected,
        "selected_text" to R.color.ui_selected_text,
        "badge" to R.color.ui_badge,
    )

    fun render(context: Context): String {
        val localized = UiPreferences.wrap(context)
        val tags = UiPreferences.languageTags(context)
        val names = context.resources.getStringArray(R.array.language_names)
        val language = localized.resources.configuration.locales[0].language
        val config = JSONObject().apply {
            put("language", language.takeIf { it in tags } ?: "en")
            put("theme", UiPreferences.theme(context))
            put("languages", JSONArray(tags.mapIndexed { index, tag ->
                JSONObject().put("tag", tag).put("name", names[index])
            }))
            put("catalogs", JSONObject().apply {
                tags.forEach { tag ->
                    val translated = UiPreferences.wrap(context, tag)
                    put(tag, JSONObject().apply {
                        strings.forEach { (key, resource) -> put(key, translated.getString(resource)) }
                        put("statuses", JSONObject().apply {
                            UiText.statuses.forEach { (key, resource) -> put(key, translated.getString(resource)) }
                        })
                        put("alarms", JSONObject().apply {
                            UiText.alarms.forEach { (key, resource) -> put(key, translated.getString(resource)) }
                        })
                    })
                }
            })
            put("palettes", JSONObject().apply {
                listOf("light", "dark").forEach { theme ->
                    val configuration = android.content.res.Configuration(context.resources.configuration)
                    configuration.uiMode = (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (theme == "light") android.content.res.Configuration.UI_MODE_NIGHT_NO
                        else android.content.res.Configuration.UI_MODE_NIGHT_YES
                    val themed = context.createConfigurationContext(configuration)
                    put(theme, JSONObject().apply {
                        colors.forEach { (key, resource) ->
                            put(key, String.format(Locale.ROOT, "#%06X", themed.getColor(resource) and 0xFFFFFF))
                        }
                    })
                }
            })
        }
        // Escape script delimiters even if a future translation contains HTML.
        val safe = config.toString().replace("<", "\\u003c").replace(">", "\\u003e")
            .replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return context.assets.open("monitor.html").bufferedReader().use { it.readText() }
            .replace("__UI_CONFIG__", safe)
    }
}
