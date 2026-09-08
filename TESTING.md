# Three-BMS phone test

## Проверка графика 0.3.9

1. Подключить BMS и подождать не менее минуты — история не существует до
   установки этой версии и должна сначала накопиться.
2. Нажать на заполненную карточку. Убедиться, что открыты отдельные цветные
   линии всех ячеек выбранной BMS.
3. Раздвигать/сдвигать два пальца, перемещать график вправо/влево и проверить
   кнопки 1 ч, 24 ч, 7 д, 30 д, 6 месяцев и «Сейчас».
4. Отключить отдельные ячейки в легенде и вернуть их.
5. Повернуть телефон, закрыть и снова открыть график. BLE-счётчики остальных
   BMS должны продолжать расти без нового подключения и писка.
6. После перезапуска приложения проверить, что накопленная история сохранилась.
7. Для аварийной BMS нажать карточку, затем кнопку аварии на графике.

Длительное шестимесячное хранение смоделировано автоматическими тестами.
Ручной тест на телефоне остаётся обязательным для оценки жестов и читаемости.

## Проверка интерфейса 0.3.8

1. Установить тестовый APK вручную; открыть приложение и дождаться данных.
2. На экране настройки переключить все три языка и обе темы.
   Проверить кнопки, диалоги, статусы и уведомление службы.
3. Во время поиска выбрать устройства, изменить язык/тему и повернуть
   телефон. Выбранные устройства и их порядок не должны сбрасываться.
4. При уже подключённых BMS менять язык/тему и возвращаться кнопкой «Назад».
   Счётчики пакетов должны продолжать расти без новой процедуры соединения.
5. Проверить SOC и подписи в обеих ориентациях, также с увеличенным шрифтом.
6. Открыть локальную браузерную страницу: проверить все языки/темы,
   сохранение выбора после обновления, диалог аварий и потерю связи.
   Аварии тестировать только на эмуляторе или тестовых данных —
   не создавать опасные условия на настоящей батарее.
7. Проверить, что JSON API содержит прежние ключи и исходные значения статусов.

Автоматические проверки: 11 JVM-тестов прошли, lint без ошибок;
браузерный тест выполняется командой node scripts/test-browser.cjs.
Ручная проверка этой версии на телефоне ещё не выполнена.

## Existing BLE regression checklist

1. Stop the stable BMS Gateway and the official JK application. Only one app
   may own each BMS connection during this test.
2. Enable Bluetooth. On Android 6–11 also enable system location.
3. Open BMS Multi Probe, grant Bluetooth/notification permissions and press
   `SCAN`.
4. Select the three BMS devices by their saved names/MAC addresses and press
   `CONNECT 3` once.
5. A JK module may confirm the initial read handshake. It must not keep beeping
   because the probe does not deliberately disconnect and reconnect sessions.
6. Wait at least two minutes. For every card verify:
   - status becomes `telemetry`;
   - packet count continues increasing;
   - voltage, current, SOC and cell count are plausible;
   - the five-second line reports `fresh 3/3`.
7. Record the displayed `packet skew`. A few seconds are acceptable for this
   experiment; exact frame synchronization is not required.
8. Turn the screen off for five minutes, then return. The foreground
   notification should remain and all counters should continue increasing.
9. Optional reconnect test: power-cycle only one BMS. The other two counters
   should continue, and the powered BMS should reuse its existing GATT client
   with bounded backoff instead of creating repeated connection clients.

Do not use `DISCONNECT` during the steady-state part of the test. The local
read-only snapshot API may be checked at `http://PHONE_IP:8766/api/v1/multi/snapshot`;
it must not interrupt BLE packet counters or reconnect behaviour.
