# Three-BMS phone test

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
