# Security

BMS Multi Probe exposes an unauthenticated HTTP server on port `8766` while its
foreground service is running. The server is intended only for a trusted local
network.

- Do not forward port `8766` through an Internet router.
- Do not place the phone on an untrusted or public Wi-Fi network while the API
  is enabled.
- Prefer a dedicated battery-monitoring VLAN where practical.
- Treat BMS names, MAC addresses and telemetry as private operational data.

The API supports read operations only. Please report security issues privately
to the repository owner rather than opening a public issue containing device
addresses or network details.
