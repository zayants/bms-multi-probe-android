# BMS Multi Probe for Android

Experimental read-only Android monitor for several JK/Jikong BMS devices at
the same time. The phone maintains persistent BLE connections, displays a
six-card fleet dashboard and exposes a local JSON API for Zabbix and other
monitoring systems.

> **Alpha software:** use this build for testing and report model-specific
> decoding issues. It is not a replacement for the protection functions of a
> correctly configured BMS.

## Download

The current test build is available from the
[v0.3.9-alpha.1 pre-release](https://github.com/zayants/bms-multi-probe-android/releases/tag/v0.3.9-alpha.1).
It adds cell-voltage history charts with pinch zoom, horizontal scrolling
and local six-month retention, plus English, Russian and Ukrainian UI
resources and two themes.
See [DESIGN.md](DESIGN.md) for customization and language-extension details.

Тестовая версия **0.3.9-alpha.1** опубликована в разделе Releases. Добавлены
графики истории напряжения всех ячеек, масштабирование, горизонтальная
прокрутка и локальное хранение до шести месяцев, три языка и две темы.
Инструкция по изменению дизайна и добавлению языков: [DESIGN.md](DESIGN.md).

- Android 8.0 or newer;
- four persistent BLE connections currently supported;
- six dashboard slots are shown for ongoing interface development;
- JK02 8S–32S telemetry variants are the main current target.

## Safety and scope

Normal operation is strictly read-only. The only writes to the JK FFE1 BLE
characteristic are requests for device information (`0x97`) and telemetry
(`0x96`). There is no BMS settings command builder in the application.

The project is not affiliated with or endorsed by JK/Jikong. Battery systems
can contain hazardous energy; the user remains responsible for BMS settings,
protective devices and safe operation.

## Quick start

1. Install the APK from the pre-release assets.
2. Stop the official JK application and other applications connected to the
   same BMS. A BMS normally accepts only one active BLE client.
3. Enable Bluetooth. Some Android versions and MIUI devices also require the
   system Location switch and precise-location permission for BLE discovery.
   The application does not read or store coordinates.
4. Press **Scan**, select up to four devices, then press **Connect**.
5. Keep the foreground-service notification enabled so Android can preserve
   the connections while the screen is off.

The monitor samples the latest packets from all sessions every five seconds.
Connections remain open between samples to avoid repeated disconnects and BMS
beeps. An unexpected disconnect uses the existing GATT client with bounded
reconnect backoff.

## Local HTTP API

The Setup screen displays the phone's LAN address. From another device on the
same trusted network, open:

```text
http://PHONE_IP:8766/
```

The machine-readable endpoint is:

```text
http://PHONE_IP:8766/api/v1/multi/snapshot
```

It includes individual cell voltages, SOC, internal temperatures, alarms,
balancing state, pack voltage, current, power, BLE state and data freshness.
See [API.md](API.md) for the complete v1 contract.

The server is unencrypted and unauthenticated. Use it only on a trusted LAN;
do not expose port `8766` directly to the Internet.

## Zabbix 7

Import [zabbix/bms-multi-probe-zabbix-7.yaml](zabbix/bms-multi-probe-zabbix-7.yaml),
link the template to a Zabbix host and set `{$BMS.MULTI.PROBE.HOST}` to the
phone's LAN IP address.

The template uses one standard Zabbix HTTP-agent request every five seconds.
Dependent discovery creates items for BMS sessions, individual cells and
internal temperature sensors. No `CurlHttpRequest` or `HttpRequest` script is
used.

## Build and verify

Use JDK 17 and an Android SDK, then run:

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
Release signing is deliberately not configured in this alpha project.

## Related project

[BMS Data Platform](https://github.com/zayants/bms-data-platform) is the
separate Android gateway and Windows dashboard project for long-term monitoring
of a single JK/Jikong BMS.

## License

Copyright © 2026 zayants. See [LICENSE.md](LICENSE.md).
