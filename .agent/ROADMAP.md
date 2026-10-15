# GameTimer — Roadmap

## Released

| Version | Highlights |
|---|---|
| v1.0.0 | Overlay countdown, interval / time-of-day modes, color zones, sound alerts, shutdown/lock/logoff, tray, Fluent UI, Windows Hello auth, EN/RU localization, installer + CI |
| v1.0.1 | Overlay re-asserts topmost every 2 s (fixes games that steal z-order) |
| v1.1.0 | Single-instance guard; keyboard controls (Enter, arrows, Ctrl+1..4, Esc) |
| v1.2.0 | Phone remote over home Wi-Fi (QR pairing, REST + WebSocket, mobile web UI), automatic firewall rule, monotonic clock (system clock changes no longer affect the timer), version in About |
| v1.3.0 | Tamper resistance: the running timer survives close / kill / reboot / logoff and resumes (or enforces if expired) at launch; "closed at HH:MM" notice on PC and phone; opt-in "Protection from closing" (deny terminate, needs a standard child account) |

---

## Phase 1 — Phone remote over home Wi-Fi (released in v1.2.0)

**Goal:** the parent sees the timer and controls it from a phone on the same Wi-Fi. No cloud, no app store: the PC app serves a mobile web UI (PWA) itself.

### Implementation order

Each step ends with a building, working app. Do not start the next step until the "Done when" check passes.

| # | Step | Spec | Status |
|---|---|---|---|
| 1 | `TimerController` refactor + monotonic clock + `Extend` | RMT-01 | ☑ |
| 2 | Embedded Kestrel host, settings toggle, LAN-only binding | RMT-02 | ☑ |
| 3 | Pairing, device tokens, auth middleware, rate limiting | RMT-04 | ☑ |
| 4 | REST API endpoints | RMT-03 | ☑ |
| 5 | WebSocket push events | RMT-03 (`/ws`) | ☑ |
| 6 | Mobile web UI (PWA) | RMT-05 | ☑ |
| 7 | Firewall rule, optional mDNS | RMT-06 | ☑ |
| 8 | Acceptance pass | Acceptance | ☑ |

**Step 1 — TimerController.** Move start/stop/reset/expiry out of `MainWindow.xaml.cs` into `TimerController` with a `StateChanged` event; the window only subscribes. Track remaining time with `Stopwatch` / `Environment.TickCount64` instead of `DateTime.Now` (closes the "system clock change" backlog item). Add `Extend(minutes)` (negative allowed, never below zero; in time-of-day mode it shifts the target).
*Done when:* desktop behaviour is unchanged; changing the system clock does not affect the remaining time; `Extend` works from a temporary debug hotkey or unit test.

**Step 2 — Embedded server.** Add `<FrameworkReference Include="Microsoft.AspNetCore.App" />`. New settings `RemoteEnabled` (default `false`) and `RemotePort` (default `5123`). "Phone control" toggle in the Settings tab, protected by Windows Hello. Host starts/stops with the toggle; it listens on all interfaces and drops connections from outside the home LAN (see RMT-02). A single unauthenticated `GET /health` returning 200 is allowed temporarily for this step only, answering only requests from this PC.
*Done when:* toggle off → nothing listens on the port; toggle on → host reachable from the PC; app exit stops the host cleanly.

**Step 3 — Pairing and security** (before any real API, so no endpoint is ever open). `DeviceStore` (device id, name, SHA-256 token hash, created, last-seen) persisted with settings. "Connect phone" dialog (Windows Hello) shows a QR with a one-time code valid for 2 minutes. `POST /pair` exchanges the code for a ≥ 256-bit random token. Bearer auth middleware with constant-time comparison on every route except `/pair` and static files. Rate limiting via built-in `Microsoft.AspNetCore.RateLimiting` on `/pair` and auth failures. Remove the temporary `/health`. Also implemented here (moved from step 4): `GET /api/devices`, `DELETE /api/devices/{id}`, and a temporary `GET /pair` page, so pairing and revocation can be tested from a real phone.
*Done when:* unpaired requests get 401; an expired or reused code fails; a paired token works; revoking a device makes its next request fail.

**Step 4 — REST API.** Implement the RMT-03 table. Every handler marshals to the UI thread with `Dispatcher.InvokeAsync` and goes through `TimerController` / `SettingsService`, never through the window.
Requests and examples: `docs/remote-api.http`.
*Done when:* all endpoints work from `curl`/HTTP file with a valid token, and actions are visible on the PC overlay.

**Step 5 — WebSocket.** `/ws` authenticated with the same token (header or first message). Events: `tick` (every 5 s), `alert`, `expired`, `started`, `stopped`, `settingsChanged`, driven by `TimerController` / `SettingsService` events.
Also added: `hello` (status + settings right after auth) and `extended` (so +/- minutes show up immediately, not with the next 5 s tick). Message reference: `docs/remote-api.http`; manual test: `docs/ws-watch.ps1`.
*Done when:* a change made on the PC appears in a connected WS client within 1 s.

**Step 6 — Mobile web UI.** Static files embedded in the exe, served at `/`. Screens: status, start, settings, paired devices. PWA manifest + icon, EN/RU following the desktop language, "PC offline" state with WS auto-reconnect.
Built as plain JS (no framework/build). Status and the start form share the Timer tab; Settings and Phones are the other tabs. No service worker (needs HTTPS), so no offline cache; home-screen shortcut works.
*Done when:* the full flow works on a real phone and the page can be added to the home screen.

**Step 7 — Network plumbing.** Decide: installer adds the Private-profile firewall rule (requires `PrivilegesRequired=admin`) or the app explains the first-run firewall prompt. Optional mDNS announce `_gametimer._tcp`.
Also (public app, many installs): when the PC's LAN IP changes, the phone's home-screen shortcut points to the old address. At least: the phone's "PC unreachable" banner suggests scanning a new QR after a while, and README suggests a DHCP reservation. mDNS (`<pc>.local`) would fix it where phones resolve `.local` (iOS yes; Android Chrome unreliable), so treat it as a bonus.
Implemented: turning on "Phone control" checks our firewall rule (`netsh ... show rule`, no admin) and, if missing, runs the exe elevated with `--configure-firewall <port>` (one UAC prompt) to delete stale rules for this exe (including block rules from Windows' own prompt) and add `SystemMonitor-Remote`: TCP on the port, this exe only, `remoteip=localsubnet`, private + public profiles (Windows 11 often marks home Wi-Fi public). Declined UAC → warning + "Allow in firewall" button; at launch only the warning (no prompt). The Settings status shows the phone URL. The phone banner switches to a "PC may have a new address, scan a new QR" hint after 20 s offline. mDNS skipped (Android Chrome doesn't resolve `.local` reliably). Known gap: the per-user uninstaller can't remove the rule (needs admin); it only allows this exe path, so a leftover is harmless.
*Done when:* a fresh install on a second machine can be reached from a phone without manual firewall setup (or with clear in-app instructions).

**Step 8 — Acceptance.** Run the checklist under "Acceptance" below and release as v1.2.0. Done: every acceptance item was verified on the real PC and phone while finishing steps 1–7 (clock change in 1, toggle off in 2, 401 / revoke in 3–5, phone ↔ PC sync in 5–6, `ws-selftest.ps1` ALL PASSED); the Release `dotnet publish` builds cleanly. The version now comes from `<Version>` in the csproj (CI passes `-p:Version=<tag>` to `dotnet publish`), and Settings → About shows it.

### Decisions and notes
- Referencing ASP.NET Core increases the self-contained single-file exe: measured 183 → 209 MB (installer ≈ 53 → 61 MB compressed). Accepted for this project.
- No new third-party packages except a QR generator (e.g. `QRCoder`, MIT) if needed for step 3.
- The `TimerController` is the single source of truth for timer state; UI, API and WS are all clients of it.

### RMT-01 Prerequisite refactor
Move start/stop/reset/expiry logic out of `MainWindow.xaml.cs` into a `TimerController` service used by both the window and the API. The window subscribes to its state changes instead of owning the logic. `TimerService` runs on the UI dispatcher, so API handlers must marshal calls with `Dispatcher.InvokeAsync`. Remaining time is computed from a monotonic clock, not `DateTime.Now`.

### RMT-02 Embedded server
- Kestrel via `<FrameworkReference Include="Microsoft.AspNetCore.App" />` (binds to LAN without admin; `HttpListener` would need `netsh urlacl` as admin).
- Port `5123` by default, configurable. **Off by default**; toggle "Phone control" in the Settings tab (toggling requires auth).
- LAN-only by remote-address filter (decided in step 2): Kestrel listens on all interfaces, `NetworkFilter` aborts every connection not from loopback, 10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10 or fc00::/7. Survives DHCP address changes without restarting the host; a VPN option would add its range in this one place.

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
- Changing the PC system clock does not change the remaining time.

---

## Phase 2 — Control from outside home, installable app

Context: GameTimer is public (other families install it), so every PC has its own address and nothing may
require per-family network setup. The home-network mode over plain HTTP (Phase 1) stays the default:
it works right after install. HTTPS is what Chrome needs for "Install app" (`beforeinstallprompt`), and a
certificate can't be issued for a LAN IP.

### 2a. Tailscale — tried, dropped (decision 2026-10-13)
- `tailscale serve --bg 5123` gives `https://<pc>.<tailnet>.ts.net` with a real certificate (installable app, control from
  anywhere). Implemented during step 6 and removed again: every family would need a Tailscale account, the app on the PC
  and the phone (a VPN slot on Android), a sign-in and an HTTPS consent click. Too much for the target users; adding
  the plain-HTTP page to the home screen by hand is simpler.
- The removed code (TailscaleInfo, QR address choice, wss CSP) is kept as git tree `794ffcd` (`git read-tree` /
  `git checkout 794ffcd -- <path>`) in case it is wanted again.

### 2b. Cloud relay (the real solution for everyone) — ☐ separate project, not started
One public HTTPS origin (e.g. `https://gametimer.<domain>`) hosts the phone PWA; each PC keeps an outbound
WebSocket to a relay; the phone talks to its PC through the relay. One URL for all installations, installable,
works anywhere, no router / firewall / VPN setup.
Draft design:
- The QR carries the PC id and a pairing secret; phone and PC derive a shared key, and all commands / events are
  **end-to-end encrypted and authenticated** (the relay only forwards opaque messages, so a compromised relay can't
  read or forge "stop" / "extend"). Device tokens stay as today, inside the encrypted channel.
- PC side: the existing API / WS handlers behind a transport abstraction (LAN Kestrel and relay connection both
  feed `TimerController`). The LAN mode keeps working when the relay is down.
- Relay: tiny stateless forwarder keyed by PC id, rate limited; hosting and cost to be chosen (small VPS or a
  serverless WebSocket platform). Needs a domain, monitoring, a privacy note.
- Open questions: account-less vs. accounts, abuse protection, how long to keep supporting it.
Estimate: a larger effort than all of Phase 1; decide after v1.2.0 is released and used.

## Phase 3 — Native app (only if needed)
For reliable background push notifications ("time is up", "timer was stopped"). .NET MAUI (shared C# models) or Flutter.

---

## Backlog / known gaps

Tamper resistance (the child uses the same Windows account, which is an administrator):
- ~~Closing the app loses the running timer.~~ Done: the running timer is persisted (`timer-state.json`) and resumed on next launch (close, kill, reboot, logoff); if the time ran out while the app was gone, the action runs at launch. Same-boot resume uses the monotonic deadline (clock-change proof); after a reboot it falls back to the wall-clock target. (TimerStateStore, TimerController.TryResume)
- ~~No sign the app was closed.~~ Done: a resume that finds a leftover running timer means the app was not exited cleanly through the tray; the parent sees "closed at HH:MM" on the PC and on the phone (`LastInterruption`, `/api/interruption/ack`).
- The process can be ended from Task Manager. Partly done: a "Protection from closing" toggle (Settings, Windows Hello, off by default) denies PROCESS_TERMINATE on the process DACL for the current user, so Task Manager "End task", `taskkill` and `Stop-Process` are refused (ProcessProtection). Applied at startup when enabled. An administrator token can still override it (take ownership / SeDebugPrivilege). A watchdog second process (relaunch after an admin kill) was considered and dropped: it only helps against an administrator-level kill — who can disable the protection anyway — while risking a hard-to-stop relaunch loop on the child's PC. The deny-terminate + resume + notification cover the realistic case.
- `settings.json` sits in a user-writable folder and can be edited. Ideas: move to `%ProgramData%` with ACLs, or sign/encrypt it. Note: from Phase 1 it also holds device token hashes.
- ~~Changing the system clock shifts the time-of-day target.~~ Fixed in Phase 1, step 1 (monotonic clock).
- ~~No usage log.~~ Done: `SessionLog` records every event (start / stop / reset / extend / expiry / resume / close) with the source (PC / phone name / system), shown on a History tab on the PC and the phone (`GET /api/history`, live over WS).

Other:
- Exclusive-fullscreen games: overlay can't draw over them (by design of Windows). Document in README.
- Overlay hover effect (SPEC OVR-02) was not implemented; seconds are always shown.
- Daily schedules / limits per weekday.
