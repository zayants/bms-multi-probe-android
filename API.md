# BMS Multi Probe local API — v1

The phone provides a **read-only** HTTP API for a monitoring system on the
same trusted LAN. It never sends a BMS settings command. The BLE connection
continues to use the existing periodic JK read requests (`0x96` telemetry and
`0x97` device information).

The app's Setup screen displays the phone URL. For example, if the phone is at
`192.168.1.50`, open this in a browser:

```text
http://192.168.1.50:8766/
```

The browser page is a convenience view. Poll the JSON endpoint from Zabbix or
another integration:

```text
GET http://PHONE_IP:8766/api/v1/multi/snapshot
```

`GET /health` returns plain text `ok`. The server supports `GET`, `HEAD` and
`OPTIONS` only. It is intentionally unauthenticated and unencrypted; never
expose the phone port to the Internet or an untrusted network.

## Snapshot contract

`apiVersion` is `1`. New fields may be added, but names and meaning of existing
v1 fields will remain compatible.

```json
{
  "apiVersion": 1,
  "mode": "multi-probe",
  "serverTime": 1788713585381,
  "sampledAt": 1788713583217,
  "sampleIntervalMs": 5000,
  "packetSkewMs": 1840,
  "sessionCount": 2,
  "freshCount": 2,
  "sessions": [
    {
      "index": 0,
      "name": "JK-BMS-01",
      "address": "AA:BB:CC:DD:EE:FF",
      "status": "telemetry",
      "connected": true,
      "packetCount": 135,
      "lastPacketAt": 1788713583021,
      "stale": false,
      "sampleAgeMs": 196,
      "gattStatus": 0,
      "timestamp": 1788713583185,
      "packVoltageV": 52.34,
      "currentA": -8.7,
      "powerW": -455.06,
      "socPercent": 78,
      "temperatureC": 23.4,
      "temperaturesC": [22.8, 23.4],
      "cellsV": [3.271, 3.272],
      "alarms": [],
      "alarmCount": 0,
      "hasAlarm": false,
      "balancingState": "off"
    }
  ]
}
```

The sample is illustrative; fields absent from a stale session are omitted.
All timestamps and durations are milliseconds since the Unix epoch / in
milliseconds, respectively.

| Field | Meaning |
| --- | --- |
| `sessions[].cellsV` | Individual cell voltages, in BMS order, volts. |
| `socPercent` | State of charge reported by the BMS, percent. |
| `temperaturesC` | All valid internal BMS temperature readings in JK frame order, °C. |
| `temperatureC` | Maximum valid internal BMS temperature — convenient aggregate for a fast alert, °C. |
| `alarms` / `alarmCount` / `hasAlarm` | Active protection and alarm states reported by JK telemetry. The list is empty when none are reported. |
| `balancingState` | `off`, `charging`, `discharging`, `unknown`, or `unavailable`. |
| `packVoltageV`, `currentA`, `powerW` | Terminal measurements reported/calculated from BMS telemetry. Negative current/power means discharge in the current JK convention. |
| `connected` | Current BLE transport state; this alone does not prove the data is current. |
| `stale` | `true` if no telemetry is present in the current five-second sample window. |
| `sampleAgeMs` | Age of the newest telemetry packet, or `null` when stale. |
| `packetSkewMs` | Difference between the newest and oldest current samples; `null` when it cannot be calculated. |

For monitoring, alert on `stale` / `sampleAgeMs` as well as BMS alarms: a
connected BLE socket is not a substitute for a fresh measurement.

## JSONPath examples

For session zero, the first cell, SOC, temperature, alarm count and fresh flag
are respectively:

```text
$.sessions[0].cellsV[0]
$.sessions[0].socPercent
$.sessions[0].temperatureC
$.sessions[0].temperaturesC[0]
$.sessions[0].alarmCount
$.sessions[0].stale
```

## Zabbix 7

Import `zabbix/bms-multi-probe-zabbix-7.yaml`, link the template to a host, and
set `{$BMS.MULTI.PROBE.HOST}` to the phone's current LAN IP (and port macro to
`8766` if changed). The template uses a standard **HTTP agent** for one raw JSON
snapshot every five seconds. It automatically discovers BMS sessions and their
cells, then creates dependent items for SOC, voltage, current, temperature,
freshness, alarm count, balancing state, and individual cell voltages.

It contains no Zabbix HTTP JavaScript and therefore does not rely on either
`CurlHttpRequest` or `HttpRequest`; the only preprocessing JavaScript builds
low-level discovery rows from the already-fetched JSON.
