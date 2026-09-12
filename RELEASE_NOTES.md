# BMS Multi Probe 0.3.10-alpha.1

Experimental read-only Android monitor for up to four simultaneous JK/Jikong
BMS connections with six dashboard slots.

This local test build adds BLE permission diagnostics and shortcuts to the
relevant Android settings. It also displays the local history database size
and free storage on the Setup screen.

The API keeps its existing JSON booleans and adds numeric `1`/`0` aliases for
Zabbix graphs. The bundled Zabbix template now uses those aliases and its
discovery scripts are limited to Duktape-compatible ES5 syntax.

BLE connection handling, GATT timeouts, reconnect behaviour and read-only BMS
requests are unchanged. This is an experimental GitHub pre-release.

## New

- Tap any populated BMS card to open individual cell-voltage history.
- One curve per cell, pinch zoom, horizontal drag and range buttons for
  1 hour, 24 hours, 7 days, 30 days and six calendar months.
- The last 24 hours retain fresh samples at roughly five-second resolution.
  Older history uses five-minute averages while preserving cell minima and
  maxima. Gaps are never interpolated.
- Cell curves can be hidden from the colour legend.
- Complete English, Russian and Ukrainian UI and browser translations.
- Light and dark themes based on shared, editable design resources.
- Alarm details remain available from the history screen.

History starts accumulating after this version is installed. It is kept only
in the application's private local database and is removed if Android clears
the app's data.

BLE scanning, connections, same-GATT reconnect behaviour and GATT timeouts are
unchanged. The only BMS requests remain the read-only device-info and telemetry
registers (0x97 and 0x96). The JSON API contract is unchanged.

This is a debug-signed, not production-signed, alpha build for testing. It has
not yet completed a long-duration multi-BMS field test.

## Русский

Добавлена локальная история напряжений всех ячеек: график по нажатию на
карточку, масштабирование двумя пальцами, горизонтальная прокрутка,
выбор периода до шести месяцев и скрытие отдельных кривых.

История начинает накапливаться после установки. Последние сутки сохраняются
с шагом около 5 секунд, старые данные — пятиминутные средние с минимумами
и максимумами. BLE и JSON API не изменены.

## Предыдущая локальная версия: 0.3.8

Первый этап: изменяемое оформление и полная локализация EN/RU/UK,
светлая/тёмная темы, сохранение состояния интерфейса при их переключении.
Подробности и ограничения: DESIGN.md. Код BLE и JSON API не изменены.

Сборка предназначена для ручной установки и проверки и опубликована как
предварительная тестовая версия.
Удалённый сервер/интернет-мониторинг в эту версию не входят.

## Предыдущая публичная версия: 0.3.7-alpha.1

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
