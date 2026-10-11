# GameTimer — Roadmap

## Released

| Version | Highlights |
|---|---|
| v1.0.0 | Overlay countdown, interval / time-of-day modes, color zones, sound alerts, shutdown/lock/logoff, tray, Fluent UI, Windows Hello auth, EN/RU localization, installer + CI |
| v1.0.1 | Overlay re-asserts topmost every 2 s (fixes games that steal z-order) |
| v1.1.0 | Single-instance guard; keyboard controls (Enter, arrows, Ctrl+1..4, Esc) |

---

## Next: Phase 1 — Phone remote over home Wi-Fi

**Goal:** the parent sees the timer and controls it from a phone on the same Wi-Fi. No cloud, no app store: the PC app serves a mobile web UI (PWA) itself.

### RMT-01 Prerequisite refactor
Move start/stop/reset/expiry logic out of `MainWindow.xaml.cs` into a `TimerController` service used by both the window and the API. The window subscribes to its state changes instead of owning the logic. `TimerService` runs on the UI dispatcher, so API handlers must marshal calls with `Dispatcher.InvokeAsync`.

### RMT-02 Embedded server
- Kestrel via `<FrameworkReference Include="Microsoft.AspNetCore.App" />` (binds to LAN without admin; `HttpListener` would need `netsh urlacl` as admin).
- Port `5123` by default, configurable. **Off by default**; toggle "Phone control" in the Settings tab (toggling requires auth).
- Bind to private network addresses only.

### RMT-03 API (JSON)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/status` | running, mode, remaining, target time, action, current zone |
| POST | `/api/timer/start` | `{ mode, hours, minutes }` |
| POST | `/api/timer/stop` | stop + cancel pending shutdown |
| POST | `/api/timer/extend` | `{ minutes }`, add (or subtract) time on a running timer |
| GET / PUT | `/api/settings` | thresholds, alerts, action, colors, language |
| GET | `/api/devices`, DELETE `/api/devices/{id}` | list / revoke paired phones |
| WS | `/ws` | push events: `tick` (every 5 s), `alert`, `expired`, `started`, `stopped`, `settingsChanged` |

### RMT-04 Pairing and security
The child is on the same network, so the API must never be open.
- "Connect phone" button (requires Windows Hello) shows a QR code: `http://<lan-ip>:<port>/pair?code=<one-time code>`. The code expires in 2 minutes.
- The phone exchanges the code for a long-lived random device token (≥ 256 bit); every request sends `Authorization: Bearer <token>`; constant-time comparison.
- Tokens stored on the PC hashed (SHA-256), not in plain text; device list with names and last-seen, revocable.
- Rate-limit `/pair` and auth failures.
- Plain HTTP on the home LAN is the accepted v1 risk. Later: self-signed TLS with the cert fingerprint pinned via the QR code.

### RMT-05 Mobile web UI
- Static files embedded in the exe, served at `/`. Mobile-first, dark, matches desktop look.
- Screens: status (big countdown in zone color + Stop / +15 min / −15 min), start form, settings, paired devices.
- PWA manifest + icon so it can be added to the home screen. EN/RU following the desktop language.
- Shows a clear "PC offline" state when the connection drops; auto-reconnects the WebSocket.

### RMT-06 Network plumbing
- Windows Firewall: inbound rule for the port on Private profile. Either the installer adds it (needs admin → change `PrivilegesRequired`) or the app explains the first-run firewall prompt.
- Optional: mDNS/DNS-SD announce `_gametimer._tcp` so the IP can change without re-pairing.

### Acceptance
- Phone pairs by scanning the QR; an unpaired device gets 401 on every endpoint.
- Start / stop / extend from the phone reflects on the PC overlay within 1 s, and vice versa.
- Revoking a device cuts it off immediately.
- With the toggle off, nothing listens on the port.

---

## Phase 2 — Control from outside home
Preferred: **Tailscale** on the PC and the phone (free personal plan). Phase 1 then works from anywhere with no code changes; only allow binding to the Tailscale interface too.
Alternative if needed: MQTT broker (e.g. HiveMQ Cloud free tier) with both sides connecting outbound, end-to-end encrypted payloads.

## Phase 3 — Native app (only if needed)
For reliable background push notifications ("time is up", "timer was stopped"). .NET MAUI (shared C# models) or Flutter.

---

## Backlog / known gaps

Tamper resistance (the child uses the same Windows account):
- The process can be killed from Task Manager. Ideas: watchdog second process, or run the enforcer as a Windows service with the UI as a client.
- `settings.json` sits in a user-writable folder and can be edited. Ideas: move to `%ProgramData%` with ACLs, or sign/encrypt it.
- Changing the system clock shifts the time-of-day target. Ideas: track elapsed time with `Stopwatch`/`Environment.TickCount64` instead of `DateTime.Now`.
- No usage log. Idea: history of sessions (start, stop, who stopped, extensions), later visible on the phone.

Other:
- Exclusive-fullscreen games: overlay can't draw over them (by design of Windows). Document in README.
- Overlay hover effect (SPEC OVR-02) was not implemented; seconds are always shown.
- Daily schedules / limits per weekday.
