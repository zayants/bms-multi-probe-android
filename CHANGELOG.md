# Changelog

## 0.3.9-alpha.1 — 2026-09-08

- Нажатие на заполненную карточку открывает график напряжений всех ячеек.
- Масштабирование двумя пальцами, горизонтальная прокрутка жестом и кнопками.
- Быстрые периоды: 1 час, сутки, 7/30 дней и шесть календарных месяцев.
- Легенда позволяет скрывать и возвращать отдельные кривые.
- Последние сутки сохраняются примерно каждые 5 секунд; архив до шести
  месяцев хранит пятиминутные средние, минимумы и максимумы.
- Пропуски связи и изменение количества ячеек не соединяются ложной линией.
- История хранится только локально, отдельно для MAC-адреса каждой BMS.
- Запись и чтение выполняются вне BLE/UI потока; очередь записи ограничена.
- Экран аварий доступен с графика. EN/RU/UK и обе темы поддержаны.
- BLE, GATT-таймауты, команды только чтения и JSON API не изменены.
- Опубликовано как тестовый GitHub pre-release.

## 0.3.8 — 2026-09-07 — локальная тестовая сборка

- Полные EN/RU/UK ресурсы интерфейса, включая статусы, аварии и уведомление.
- Переключение языка и светлой/тёмной темы с сохранением выбора.
- Общие палитры и размеры карточек; отдельная браузерная разметка.
- Сохранение выбора устройств и экрана при пересоздании интерфейса.
- Компактные горизонтальные карточки с автоматическим подбором размера SOC.
- Переведённая браузерная страница, диалог аварий и скрытие старых чисел
  при потере связи с API.
- Проверки переводов, контраста, статусов и браузерного поведения.
- BLE-менеджер, протокол, таймауты, выборка и JSON-контракт не изменены.
- В Интернет/GitHub не опубликовано. Удалённое соединение пока не добавлено.

## 0.3.7-alpha.1 — 2026-09-06

- persistent monitoring for up to four simultaneous JK/Jikong BLE sessions;
- six-card portrait and landscape dashboard;
- stable scan-result ordering and clear device selection;
- same-GATT bounded automatic reconnect;
- read-only local HTTP/JSON API on port `8766`;
- browser-readable live fleet page;
- individual cell voltages and internal temperature readings;
- JK runtime alarms with red card indication and details;
- balancing state, connection state and data-freshness reporting;
- ready-to-import Zabbix 7 HTTP-agent template.

This is an experimental test release. No release signing configuration or BMS
settings-write functionality is included.
