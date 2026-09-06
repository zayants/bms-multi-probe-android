package com.zayants.bmsmultiprobe

import com.zayants.bmsmultiprobe.model.ProbeSessionState
import com.zayants.bmsmultiprobe.model.ProbeWindow

object SampleWindow {
    const val INTERVAL_MS = 5_000L
    const val FRESHNESS_MS = 15_000L

    fun create(now: Long, sessions: List<ProbeSessionState>): ProbeWindow {
        val sampled = sessions.map { state ->
            state.copy(
                sampledAt = now,
                telemetry = state.telemetry?.takeIf { now - it.timestamp <= FRESHNESS_MS },
            )
        }
        val timestamps = sampled.mapNotNull { it.telemetry?.timestamp }
        val skew = if (timestamps.size >= 2) {
            (timestamps.maxOrNull() ?: now) - (timestamps.minOrNull() ?: now)
        } else {
            null
        }
        return ProbeWindow(now, sampled, skew)
    }
}
