# GameTimer

Parental-control game timer for Windows. A click-through, always-on-top countdown overlay sits on top of games; when time runs out the PC shuts down / locks / logs off. Controls are protected by Windows Hello so the child (9 y.o.) can't stop the timer.

Owner: Dmytro (GitHub: DmitroShapovalov/GameTimer). The app is used at home on the son's PC.

## Start here

| File | What it is |
|---|---|
| `CLAUDE.md` | This file: current state, conventions, gotchas. Read first. |
| `.agent/VISION.md` | **Product north star:** cross-device parental control (hub + pluggable agents), the no-server privacy model, honest per-platform limits, and the phased plan. Read for direction. |
| `.agent/ROADMAP.md` | Released versions, the **next planned phase**, backlog and known gaps. |
| `.agent/SPEC.md`, `.agent/PLAN.md` | Original v1.0 spec and plan (historical, RU). Implemented; some details superseded. |
| `README.md` | Public user-facing docs (EN): features, shortcuts, build, download. |

## Working agreements

- Talk to the owner in **Russian**. Code, comments, commit messages, README and docs in **English**.
- The agent compiles the code itself (see "Agent environment notes"), but can't run the WPF app: the owner tests behaviour on his Windows PC.
- Commit only when asked ("коммить"). Tags and releases are done by the owner. Feature work goes to a branch and a PR to `master`.
- Keep UI text localized in all four languages (en / ru / nl / uk): Windows `Strings*.resx`, the PWA `app.js` STR blocks, and the Android `res/values*/strings.xml` (see Localization).
- Before larger features, propose the approach briefly; for UI changes he usually sends screenshots with feedback.
- **Independent review of every PR (owner's rule).** Before handing a PR to the owner, run a fresh
  subagent as an adversarial reviewer. It gets the diff / changed files and the goal of the change, not
  the author's reasoning, and reports findings with severity, a failure scenario and a fix. Then:
  - fix real defects in the same PR; for anything not fixed, say why;
  - add an **"Independent review"** section to the PR description: what was found, fixed, declined;
  - security-relevant changes (crypto, pairing, auth, tamper resistance, installer / update, API) get a
    second pass after the fixes;
  - trivial PRs (version bump, typo) may skip it — say so in the description.
  The owner checks behaviour on real devices; the reviewer catches what he can't check in the code.

## Stack

- .NET 8, WPF, `net8.0-windows10.0.17763.0` (needed for WinRT `UserConsentVerifier`)
- [WPF UI](https://github.com/lepoco/wpfui) 3.0.5 (Fluent, dark theme, blue accent `#0078D4`)
- WinForms only for `NotifyIcon` (tray) and `ColorDialog`
- Single-file, self-contained, win-x64. `AssemblyName` = `SystemMonitor` (deliberately neutral so the child doesn't notice it in Task Manager)

## Structure

```
src/GameTimer/
  App.xaml(.cs)            Single-instance guard (named mutex + show event), creates MainWindow manually
  Models/AppSettings.cs    All settings (JSON), AlertPoint, enums TimerAction / TimerMode
  Models/PairedDevice.cs   Paired phone: id, name, SHA-256 token hash, created / last seen (UTC)
  Services/
    TimerController.cs     Single source of truth: Start/Stop/Reset/Extend, State, StateChanged; runs the expiry action
    TimerService.cs        DispatcherTimer countdown on a monotonic clock (TickCount64); events Tick, AlertTriggered, TimerExpired
    SettingsService.cs     settings.json next to the exe
    RemoteServer.cs        Embedded Kestrel for the phone remote: LAN filter, rate limits, bearer auth, pairing, devices
    RemoteServer.Api.cs    Timer and settings endpoints, DTOs, validation (partial class)
    RemoteServer.Events.cs /ws endpoint: first-message auth, event subscriptions → broadcasts (partial class)
    RemoteServer.V2.cs     Protocol v2: /v2 + /v2/pair (Origin → 403, slots → 503) into AgentHost; V2Backend maps
                           requests to TimerController / settings (rev, ifRev); v2 events from the Events.cs handlers;
                           runs the mDNS advertiser (discovery, §9) while the server runs
    V2/AgentFileStore.cs   v2 state (PC key pair, paired parent apps, rid cache) in DPAPI file remote-v2.json
    V2/WindowsPairingUi.cs IPairingUi: asks the owner for the phone's 6-digit code (PairingCodeDialog + Windows Hello)
    WebSocketHub.cs        Open sockets: per-connection outbox + send loop, broadcast, disconnect, graceful close
    NetworkFilter.cs       Allows only loopback / private LAN remote addresses; finds the PC's LAN IP for the QR
    DeviceStore.cs         Paired devices: Add/Revoke on UI thread, thread-safe token lookup (snapshot)
    PairingService.cs      One-time 8-char pairing code, 2-minute lifetime, single active code
    UiThread.cs            SynchronizationContext wrapper: InvokeAsync/Post to the UI thread, VerifyAccess
    FirewallHelper.cs      Windows Firewall rule `SystemMonitor-Remote`: check via netsh (no admin), apply elevated
    ShutdownService.cs     shutdown / lock / logoff, CancelShutdown
    SoundService.cs        MediaPlayer; built-in Resources/notification.mp3 (embedded) or custom file
  Helpers/
    NativeHelper.cs        Click-through + toolwindow styles, periodic HWND_TOPMOST re-assert
    PasswordHelper.cs      Fallback auth via LogonUser (Windows password), lockout after failures
  Localization/
    Loc.cs                 Loc.Get("Key"), Loc.SetLanguage("en"|"ru")
    Strings.resx           English (default)
    Strings.ru.resx        Russian
    Strings.nl.resx        Dutch
    Strings.uk.resx        Ukrainian
  Windows/
    MainWindow             FluentWindow, 4 tabs (Timer, Display, Notifications, Settings), tray, keyboard
    OverlayWindow          Countdown overlay, color zones, pulse animation
    PasswordDialog         Fallback when Windows Hello is unavailable
    ConnectPhoneDialog     QR code with the one-time pairing link (v1 code in the query, v2 secret in the #fragment),
                           countdown, "connected" state, "two phones" warning
    PairingCodeDialog      v2 pairing: owner types the code the phone shows, then Windows Hello
  Remote/                  Phone UI (plain HTML/CSS/JS, no build step), embedded by LogicalName ("Remote/app.js"):
                           index.html, app.js (EN/RU strings inside), app.css, manifest.webmanifest, icons
installer/setup.iss        Inno Setup script (per-user install, optional autostart)
docs/remote-api.http       Every phone API request and WebSocket message, with examples (REST Client format)
docs/ws-watch.ps1          PowerShell: pair with a code and print /ws events with timestamps
docs/SECURITY.md           Threat model of the phone remote, residual risks, dependencies
docs/ws-selftest.ps1       PowerShell 5.1/7: end-to-end check of pairing, API and /ws events on the real PC (PASS/FAIL)
.github/workflows/release.yml   PRs: build installer artifact; tag v*: GitHub Release
.github/workflows/protocol.yml  Protocol core: C# + Kotlin vectors/behaviour tests, live Kotlin <-> C# interop, iOS simulator
.github/workflows/android.yml   Android app: debug APK artifact, on-device test on an emulator against the C# test agent

src/GameTimer.Protocol/    Protocol v2 core in C# (net8.0, no WPF), no web server or UI inside:
  Noise/                   IK + IKpsk1 on libsodium (Sodium.Core)
  Encodings/, Identity/    base32 / base64url, agent and hub ids, the 6-digit pairing code, discovery tags,
                           relay route tokens (§10.1)
  Messages/StrictJson.cs   I-JSON parsing (duplicate names, 2^53 integers) → ProtocolViolation
  Agent/AgentHost*.cs      The agent side: admission + limits, /v2 handshake and the ready rule, hello,
                           requests (capabilities, roles, rid cache, §5 ordering via an explicit RequestOrigin),
                           /v2/pair (secret window, two-phones rule, typed code), peers and roles.
                           The app plugs in IAgentBackend (timer etc.), IPairingUi, IAgentStore, IFrameTransport
  Relay/                   §10: RelayClient (one outbound wss to the relay; registers the claims from
                           AgentHost.RelayRoutes when the set changes, ≤ 1/s; opens → AgentHost.TryAdmitRelay
                           (own pool 4 / 2 per hub, evicts the oldest; msg 1 within 2 s; route must match the
                           hub key) → AgentHost.RunAsync; keepalive 30 / 90 s; back-off 1 s → 5 min), RelayFrames
  Discovery/               §9: MdnsMessage (DNS wire format), MdnsResponder (pure answers + announcements),
                           MdnsAdvertiser (UDP 5353 shared socket, multicast/QU/legacy replies, re-reads the service)
src/GameTimer.Relay/       Phase B relay (protocol §10), ASP.NET Core, no storage: /v2/agent (framed, many streams),
                           /v2/hub/<route> (one stream), /healthz. Registry (route → agent socket, routes computed
                           from the registered claims), AgentSession / HubStream (outboxes, close codes, buffers),
                           IpLimits (per IPv4 / IPv6 /64), Sweeper (register deadline, keepalive, idle streams).
                           Logs nothing below Warning for ASP.NET Core (paths contain routes). Dockerfile + fly.toml
                           (app gametimer-relay, region ams, one shared-cpu-1x 256 MB machine, never stopped);
                           .github/workflows/relay.yml tests every PR and deploys master (environment "relay",
                           secret FLY_API_TOKEN = a deploy token for that app only)
tests/GameTimer.Relay.Tests/  xunit over TestServer WebSockets: joining, close codes, upgrade answers, limits, frames
shared/                    Protocol v2 core in Kotlin Multiplatform (jvm, android, iosArm64, iosSimulatorArm64):
                           noise/ (mirrors the C# classes 1:1, libsodium via ionspin), encodings/, identity/,
                           hub/ = the parent-app side: PairingLink (QR), pair(), HubSession (IK + ready + hello,
                           requests, events, ping), AgentLink (reconnect, latest state, resend with the same rid,
                           Locator search by discovery tag after a failed connect; with a RelayPath, §10.3: LAN
                           first, the relay after 2 s / at once off Wi-Fi / as soon as the LAN failed, only while
                           PairedAgent.remoteAccess (from hello / ev.settings, off on bye remote_off); the first
                           handshake to finish gets ready (HubSession.handshake → PendingSession.confirm), the
                           other is abandoned; LinkStatus.Online.viaRelay), DiscoveryTxt,
                           KtorConnector (WebSocket: OkHttp on Android/JVM, Darwin on iOS),
                           Models + AgentApi (typed §7.2 calls; writes send ifRev, conflicts return the current value)
android/                   Parent app (Kotlin, Jetpack Compose, minSdk 26, applicationId com.gametimer.parent):
  HubStore.kt              hub key (AES-GCM wrapped by an Android Keystore key) + paired PCs, in noBackupFilesDir
  LinkHub.kt               App-wide: the hub key, one AgentLink per PC (with the relay path, RelayPath.DEFAULT_URL;
                           remoteAccess stored per PC by HubStore.updateRemoteAccess) (connected while the UI is visible or
                           MonitorService runs), the "new parent app added" queue (peer list compared per PC),
                           events → Notifier
  Notifier.kt              NotifySettings (background + 4 categories) and system notifications: history caught
                           up on every connect back to the last event seen (by the event itself, not its time —
                           the PC clock may be off), ev.alert, parent added; none for the PC whose screen is
                           open; this phone's own actions skipped by hub id (history `hub`, agent v1.7.2)
  MonitorService.kt        connectedDevice foreground service (quiet ongoing notification) keeping the links
                           open in the background, only on Wi-Fi / Ethernet; BootReceiver restarts it;
                           MonitorService.update(): on when enabled, a PC paired and notifications allowed
  AppSettingsScreen.kt     This phone's settings (gear on the home screen): notifications, permission, battery
  MainViewModel.kt         UI state over LinkHub: identity, pairing flow, commands, app lock, messages
  ReAuth.kt                §12 re-auth with the phone's lock (androidx.biometric: strong biometric or PIN, weak on 9-10):
                           app lock after 14 days, check before admin actions (roles, revoking another parent; 60 s grace)
  NsdLocator.kt            Locator on NsdManager: browse _sysmon._tcp, resolve one at a time, match TXT tags
  MainActivity.kt          Compose screens: PCs list, QR scan (or paste link), pairing code, PC screen with tabs
  PcTabs.kt                PC tabs (protocol §7.2 via shared AgentApi): Timer (for / until, presets, zone colours,
                           interruption, schedule chip), Schedule, Settings (rev / conflict), History (paged), Parents (roles, revoke)
  QrScanner.kt             CameraX + ML Kit (on-device) QR reader
  res/values*/strings.xml  EN / RU / NL / UK
  src/androidTest/         On-device test (CI emulator): Keystore key, libsodium JNI, pairing + control of TestAgent
tests/vectors/             Published Noise vectors (cacophony, snow) + protocol-vectors.json (ids, pairing code,
                           encodings; generated by an independent Python script), used by both cores
tests/GameTimer.Protocol.Tests/  xunit: vectors + behaviour tests for the C# core
tests/interop/NoisePeer/   Test-only C# peer driven over stdin by shared/src/jvmTest/.../NoiseInteropTests.kt
tests/interop/TestAgent/   Test-only: the real C# AgentHost behind Kestrel, scripted by .../hub/HubInteropTests.kt
tools/GameTimer.TestHub/   gt-testhub.exe: scripted v2 parent app for testing on the real PC (CI artifact gt-testhub-win-x64)
```

The Gradle root is the repo root (`settings.gradle.kts`, `gradlew`, `gradle/libs.versions.toml`); the .NET projects build separately with `dotnet`. Any change to one Noise core is made in the other too, and both must keep passing the vectors and the interop test.

`App` creates `SettingsService`, `AppSettings` and `TimerController` and passes them to `MainWindow`. The window is only a client of `TimerController`: it calls its methods and updates the UI in `OnTimerStateChanged`. Every control surface (window, phone API) must go through the controller, on the UI thread (`Dispatcher.InvokeAsync` from background threads; the controller calls `VerifyAccess`).

## Key behaviours

- **Auth:** `RequireAuth()` in MainWindow: Windows Hello (PIN / fingerprint / face) → fallback `PasswordDialog`. Required for Stop, Reset, changing the expiry action, and Exit while a timer runs.
- **Tray:** closing the window always hides to tray; real exit only from the tray menu. `ShutdownMode=OnExplicitShutdown`.
- **Single instance:** second launch signals the first (`Local\SystemMonitor_7F3A2C1E_*`) and exits; first calls `MainWindow.BringToFront()`.
- **Update / uninstall:** the app ignores the Restart Manager by design, so `setup.iss` has `CloseApplications=no` and its `[Code]` asks the app to close itself. Object names live in `Services/SetupSignals.cs` (keep `setup.iss` in sync). Setup holds `…_SetupWaiting`, sets `…_Exit` (update) or `…_Uninstall`, and waits up to 60 s for the instance mutex to vanish (Retry / Cancel; an older version without the signal gets "exit from the tray"). The app (`MainWindow.ExitForSetup`) asks Windows Hello under the tray-exit rule (`ExitNeedsAuth`: timer running or schedule on), closes only if `…_SetupWaiting` still exists, and then: **update** → `TimerController.PrepareForUpdate` logs `Updated` and saves a running timer with `ClosedForUpdate` + `AppVersion`; **uninstall** → stops the timer (and `[UninstallDelete]` removes `timer-state.json*`, so a reinstall never starts "expired"). `TryResume` treats it as clean only if a *different* version starts within 10 min; otherwise it is reported like any unclean close. An unacknowledged "closed at" notice is carried across restarts (`PendingInterruption`). The instance mutex is **not** released in `OnExit` — Windows releases it at process teardown, so Setup can't race a still-locked exe. Setup relaunches the app as the original user after a successful install, and also if the install / uninstall didn't complete.
- **CI:** `release.yml` builds the installer on every PR (artifact, 14 days, read-only token) and publishes a Release only on `v*` tags in a separate job with write access.
- **Overlay position:** stored as 0..1 fractions mapped to `[0, screen - overlaySize]`.
- **Keyboard:** Enter = start / add alert, ↑↓ (+Shift) adjust, `:` jumps to minutes, Ctrl+1..6 tabs, Esc = tray.
- **Phone control:** toggle in the Settings tab (auth required) starts/stops `RemoteServer` on `RemotePort` (5123); `RemoteEnabled` is restored at launch without a prompt. `App.OnExit` stops the host. The status line shows the phone URL (`http://<lan-ip>:<port>`).
- **Discovery (protocol §9):** while the server runs, at least one v2 parent app is paired and the PC is on a network where a parent app already had a session (`AgentHost.KnownNetworks`, id = `NetworkFilter.GetNetworkId`: hash of subnet + gateway MAC via SendARP; remembered on `AgentHost.SessionStarted` from a LAN address), `MdnsAdvertiser` announces `_sysmon._tcp` (neutral on purpose) with random instance / host labels (new every UTC day and app start, never the computer name), the LAN IPv4 from `NetworkFilter.GetLanAddress` and TXT `v=2`, `tags=…` (today's UTC tag per paired hub, `AgentHost.DiscoveryTags`). It answers only queries from the LAN interface's subnet (unicast replies rate-limited), re-reads the service every 30 s, on `PeersChanged` and on a network address change (retrying a failed bind), re-announces on any change, and sends a goodbye on stop. The app's `AgentLink` asks its `Locator` only when no session could be opened (every 20 s, doubling to 5 min; tags for yesterday / today / tomorrow; loopback answers ignored) and tries every found LAN address and adopts one only after the handshake with the pinned key succeeded there; `HubStore.updateAddress` then stores it.
- **Control from anywhere (protocol §10, Phase B):** `AppSettings.RemoteAccess` (off by default; toggle under phone control, Windows Hello both ways). `RemoteServer.Relay.cs` runs a `RelayClient` while the server runs, the setting is on and a parent app is paired (`UpdateRelayAsync` on start / stop / `PeersChanged` / the toggle); `AgentHost.RemoteAccess` goes into `hello`, `remoteAccess` into the v2 settings (read-only; a change bumps the rev). Turning it off ends relayed sessions with `bye remote_off`. Relayed sessions never count as "this network is known" (§9). `RelayUrl` (settings.json only) overrides the built-in `wss://gametimer-relay.fly.dev/v2/agent`. `gt-testhub --relay <command>` goes through the relay.
- **Firewall:** two rules: TCP on the remote port (`SystemMonitor-Remote`) and UDP 5353 for discovery (`SystemMonitor-Discovery`), both for this exe and the local subnet only; `IsConfiguredAsync` needs both, so an older install asks for UAC once more. Enabling phone control adds the rules first (one UAC prompt) by running this exe with `--configure-firewall <port>`; `App.OnStartup` handles that switch before the single-instance mutex, calls `FirewallHelper.Apply` and exits with its code. netsh output is localized: only the exit code, port and path are parsed.
- **Phone pairing & auth:** "Connect phone" (auth) shows a QR for `http://<lan-ip>:<port>/pair?code=…`. `POST /pair {code, name}` → `{deviceId, name, token}`; the token is 256-bit base64url, stored on the PC only as a SHA-256 hash. Everything under `/api` and `/ws` needs `Authorization: Bearer <token>` (401; 429 after 10 failures/min per IP). `/pair` is limited to 5 POSTs/min per IP. Any paired phone can list / revoke devices; the PC can revoke from the Settings tab (auth).
- **Phone API:** see `docs/remote-api.http`. JSON camelCase, enums as camelCase strings (numbers rejected). Handlers run on the UI thread via `UiThread.InvokeAsync` and only use `TimerController` / `SettingsService`. `PUT /api/settings` is partial and validated, then `SettingsService.NotifyChanged(server)`; `MainWindow` refreshes its controls for any change whose source isn't itself. Color zone logic lives in `AppSettings.GetZone` (overlay and API).
- **Phone UI:** served at `/` (and `/pair?code=…` from the QR) with CSP `script-src 'self'` (no inline scripts/styles), `no-cache`, `nosniff`. Token in `localStorage` (`gametimer.token`). The countdown runs locally between 5 s ticks; language follows the PC setting; on close 1008 `revoked`/`unauthorized` or HTTP 401 it forgets the token and shows the pairing screen. No service worker (not needed for installing; no offline cache).
- **No HTTPS (decided):** the phone UI is plain HTTP on the LAN. Chrome's install prompt needs HTTPS, so the Phones tab shows manual "Add to Home screen" steps (Android / iOS) instead; the `beforeinstallprompt` button only shows up if the UI is ever served over HTTPS. Tailscale (`tailscale serve`) was tried and dropped as too complex for the target users; a snapshot is kept as git tree `794ffcd` (see ROADMAP Phase 2).
- **Timer controls (protocol minor 1, v1.7.1):** "Lift restrictions" = `TimerController.Lift(skipScheduleToday)`: stops the timer without a lock and, if chosen, sets the schedule aside for today (`UsageTracker.ScheduleSkippedToday`, in `usage.json`, rolls over at midnight; a timer started during the lift isn't capped and ends the lift when it runs out, `EndLiftOnExpiry`; history `ScheduleSkipped` / `ScheduleRestored`; `ScheduleStatus.SkippedToday`, `ScheduleDecision.Restricts` = schedule limits anything). Works without a running timer while the schedule limits today. "End now" = `EndNow` (expiry action at once, logged as −N min). "End at" = `SetEnd(h, m)` (next occurrence, capped by the schedule; more than 12 h ahead → invalid_time, no later end allowed → schedule_limit). On the PC: step ComboBox (1/5/10/15/30/60, `AdjustStepMinutes`) with − / +, an end-time field, End now and Lift; more time (+, a later end, Lift) needs Windows Hello, less time doesn't. The phone shows these only when `hello.minor` ≥ 1 (`AgentApi.supportsTimerControls`); older agents keep Stop and ±.
- **Extend event order:** `Extended` is raised first, then `TimerService.EvaluateNow()` may raise an alert or the expiry.
- **WebSocket `/ws`:** auth by bearer header or first message `{"type":"auth","token"}` (5 s). Events come from `TimerController` (`StateChanged`, `Tick` throttled to 5 s, `AlertTriggered`, `Extended`), `SettingsService.SettingsChanged` (sent only if the settings DTO changed) and `DeviceStore.Changed` (revoked devices are disconnected). Subscribed while the server runs.
- **Resilience (parental control, opt-in):** a running timer is persisted to `timer-state.json` and resumed on next launch (close / kill / reboot / logoff) by `TimerController.TryResume`; if it expired while gone, the action runs at launch. A resume with leftover state is treated as an unclean close and surfaced as `LastInterruption` (PC InfoBar + phone). "Protection from closing" (Settings, Windows Hello, `AppSettings.TamperProtection`, off by default) calls `ProcessProtection.DenyTermination` to refuse Task Manager / taskkill; an admin token can still override it.
- **Daily schedule (parental control, opt-in):** per-weekday `DaySchedule` (allowed, minute budget, allowed-hours window in minutes-from-midnight) in `AppSettings.Week` + `ScheduleEnabled` (off by default). `ScheduleEvaluator.Evaluate(now, enabled, week, usedSecondsToday)` is pure (no clock/state) → `ScheduleDecision` (Status, AvailableSeconds). `TimerController` owns a `UsageTracker` (today's used seconds in `usage.json`, rolls over at local midnight), auto-starts the day's allowance at launch and on session-unlock (`AutoStartIfScheduled`), and a 30 s `ScheduleCheck` locks (runs the expiry action) once per transition when blocked and caps a running or manually-started timer to the remaining allowance (`CapToSchedule`). Exposed over the API as `scheduleStatus` / `scheduleAvailableSeconds` / `usedSecondsToday` on status, `GET`/`PUT /api/schedule`, and the WS `schedule` event. Editable on the PC Schedule tab (Ctrl+6; the editor unlocks with Windows Hello and re-locks when the window hides) and the phone Settings screen. Disabling the schedule needs Windows Hello, so it can't lock the parent out.
- **Tamper-resistant state (parental control):** `settings.json`, `usage.json` and `timer-state.json` go through `ProtectedStore` — DPAPI (WinRT `DataProtectionProvider`, `LOCAL=user`), a 4-byte `GTP1` magic, and a `.bak` of the last good copy. On load a tampered file is rolled back from `.bak` (or defaults if none) and a `SessionEventType.Tampered` event is logged, which `App.OnStartup` drains from `ProtectedStore.DrainTamperReports()` after all state is loaded; it shows in the History on the PC and phone. One-time plaintext migration: a no-magic file with no `.bak` yet is accepted as legacy and re-saved protected. ACLs are useless here (the app runs as the child); DPAPI is reversible for the same user, so this stops hand-editing, not code run as that user.
- **Session history:** `SessionLog` appends every timer event (started / stopped / reset / extended / expired / resumed / closed) to `sessions.jsonl` with the source (PC, phone + device name, or system). Shown on a History tab on the PC and the phone; the phone reads `GET /api/history` and refreshes on the WS `history` event. `TimerController` records them (methods take `ActionSource source, string? device`).
- **Clock:** remaining time is monotonic (`Environment.TickCount64`): system clock changes don't affect it, sleep time still counts. Time-of-day mode reads the wall clock once at start.
- **Debug-only hotkeys:** Ctrl+Shift+Plus / Minus = `Extend(±1)` (temporary, for testing).
- **Validation:** empty time field = 0; inline error text under inputs disables Start.

## Localization

Add every new string to **all** `Strings*.resx` files (en, ru, nl, uk — same key in each), set it in `ApplyLocalization()`. The phone has its own copy in `Remote/app.js` (`STR.en/ru/nl/uk`); keep it in sync. A new language also needs: a `ComboBoxItem` in `MainWindow.xaml`, an `<option>` in `Remote/index.html`, an `STR` block in `app.js`, and the code in the `Languages` whitelist in `RemoteServer.Api.cs`. Language switches at runtime without restart. Don't add `<EmbeddedResource Include=...>` for resx: the SDK includes them automatically (NETSDK1022 duplicate error).

## Gotchas (compile errors we already hit)

- WebSockets: never cancel a pending `ReceiveAsync` (it aborts the socket and the close reason is lost). Close by sending `CloseOutputAsync` and waiting for the echo (`WebSocketHub.CloseAsync`).
- WPF projects don't get `System.IO` in implicit usings: add `using System.IO;` for `StreamReader`, `MemoryStream` etc.

- Namespace `GameTimer.Windows` shadows WinRT `Windows.*`: use `global::Windows.Security...`.
- WPF / WinForms / WPF UI type ambiguity: write `System.Windows.MessageBoxButton`, `System.Windows.Input.KeyEventArgs`, `System.Windows.Forms.NotifyIcon` etc. fully qualified.
- `SoundPlayer` can't play MP3: use `System.Windows.Media.MediaPlayer`.
- Exclusive-fullscreen games (old StarCraft) bypass the compositor: no overlay can draw over them. Borderless/windowed-fullscreen works.

## Build & release

```powershell
cd src/GameTimer
dotnet build
dotnet publish -c Release -r win-x64 --self-contained -o ../../publish
```

Protocol core (Linux/macOS/Windows; JDK 21):

```bash
dotnet test tests/GameTimer.Protocol.Tests/GameTimer.Protocol.Tests.csproj
dotnet test tests/GameTimer.Relay.Tests/GameTimer.Relay.Tests.csproj    # the relay
dotnet build tests/interop/NoisePeer/NoisePeer.csproj -c Release -o /tmp/peer
dotnet build tests/interop/TestAgent/TestAgent.csproj -c Release -o /tmp/agent
./gradlew :shared:jvmTest -PcsharpPeer=/tmp/peer/NoisePeer.dll -PcsharpAgent=/tmp/agent/TestAgent.dll   # without them the interop tests are skipped (CI fails instead)
```

Release: `git tag vX.Y.Z && git push origin vX.Y.Z` → Actions builds `GameTimerSetup.exe` and the signed parent app `GameTimerParent-X.Y.Z.apk` (tags `vX.Y.Z` or `vX.Y.Z-rcN` only; versionName X.Y.Z, versionCode X·10⁶+Y·10⁴+Z·100+(N or 99)) and attaches both to the GitHub Release. The APK release key exists only in the GitHub environment `release` (deployment rule: tags `v*`) as secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` and the owner's backup (`D:\Projects\_claude\gametimer-release.*`, never in the repo); a tag without them fails. Changing the key means every phone reinstalls and pairs again. Released so far: v1.0.0, v1.0.1, v1.1.0, v1.2.0, v1.3.0, v1.4.0, v1.5.0, v1.6.0, v1.6.1, v1.7.0 (protocol v2 + the Android parent app), v1.7.1 (timer controls: lift restrictions, end now, end at, flexible steps), v1.7.2 (phone notifications while the app is closed). Bump `<Version>` in the csproj for local builds; CI takes the version from the tag.

## Agent environment notes (Cowork)

- The repo lives on the owner's PC at `D:\Projects\GameTimer`, reached through the connected folder (`device_bash`, path `$HOME/mnt/Projects/GameTimer`).
- Git leaves stale `.git/index.lock` / `HEAD.lock` from the sandbox: request delete permission for `D:\Projects`, then `rm -f` them before committing.
- Commit message attribution lines come from the session's system instructions.
- **Push / PR (GitHub access):** follow the owner's `personal-github-access` skill. In short: a fine-grained PAT (personal account only, selected repos; Contents, Pull requests, Workflows, Actions, Issues RW; no Administration / Secrets) lives in `D:\Projects\_claude\github-credentials` (git store format), shared by all his projects. Use it per command, never write it into `.git/config` (Windows git reads that file too), never print it:
  `git -c credential.helper= -c "credential.helper=store --file=$HOME/mnt/Projects/_claude/github-credentials" push -u origin <branch>`.
  **Never use the GitKraken tools**: they use the owner's Windows git credentials, which also reach his employer's repositories.
- After a git command that left `.lock` files (no delete permission yet), request delete permission for `D:\Projects` and remove them before the next git command.
- **Building from the agent (compile check).** `device_bash` runs in a Linux VM, not Windows, with network access. Install the .NET 8 SDK into the session home (it does not persist between sessions, ~1 min), then build a copy outside the repo so Linux `bin/obj` never mix with the owner's Windows build:
  ```bash
  curl -sSL https://dot.net/v1/dotnet-install.sh -o ~/di.sh && bash ~/di.sh --channel 8.0 --install-dir ~/.dotnet
  export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
  rm -rf ~/build && mkdir -p ~/build && cd ~/mnt/Projects/GameTimer/src \
    && tar --exclude=bin --exclude=obj -cf - GameTimer | tar -xf - -C ~/build
  cd ~/build/GameTimer && ~/.dotnet/dotnet build -p:EnableWindowsTargeting=true -v q
  ```
  `EnableWindowsTargeting` lets Linux compile `net8.0-windows` (WPF + WinForms). The app can't run there: behaviour is tested by the owner on Windows.
- **Android in the agent VM.** The VM has 4 GB RAM and no KVM (no emulator): build with `./gradlew :android:assembleDebug --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs="-Xmx1800m -XX:MaxMetaspaceSize=512m"`, otherwise Gradle is killed silently (out of memory). compileSdk 37 (Compose needs it). On-device behaviour is tested in CI (emulator) and by the owner on his phone.
- **Kotlin in the agent VM.** Install a Temurin JDK 21 into `~/tools/jdk21` (Adoptium API tarball), export `JAVA_HOME`, and run `./gradlew` in an rsync copy (`rsync -a --exclude .git --exclude bin --exclude obj --exclude .gradle --exclude build ~/mnt/Projects/GameTimer/ ~/build/GameTimer/`) so Gradle caches never land in the owner's folder. `dotnet` must be on `PATH` for the interop test. iOS targets are skipped on Linux (`kotlin.native.ignoreDisabledTargets`); they build in the macOS CI job. The android target needs an Android SDK: cmdline-tools into `~/tools/android`, `sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"`, and `sdk.dir=…` in the build copy's `local.properties` (never in the repo).
