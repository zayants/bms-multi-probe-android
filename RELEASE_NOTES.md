# BMS Multi Probe 0.3.7-alpha.1

Initial public test build of the separate multi-BMS Android monitor.

## Included

- up to four persistent JK/Jikong BLE connections;
- read-only telemetry requests only (`0x96` and `0x97`);
- responsive six-card dashboard;
- local browser view and versioned JSON API on port `8766`;
- individual cells, internal temperatures, SOC, alarms and balancing status;
- Zabbix 7 template using the standard HTTP agent and low-level discovery.

## Installation

Download the APK, allow installation from your chosen file manager if Android
requests it, and install the application manually. Close the official JK app
before connecting because a BMS normally accepts one BLE client at a time.

The APK is a debug-signed experimental build. It is not a production release.

## Zabbix

Download the YAML template from the release assets, import it into Zabbix 7,
link it to a host and set `{$BMS.MULTI.PROBE.HOST}` to the phone's LAN IP.

Use the API only on a trusted local network. It has no authentication or HTTPS.
