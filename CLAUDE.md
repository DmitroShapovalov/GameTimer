# GameTimer

Parental-control game timer for Windows. A click-through, always-on-top countdown overlay sits on top of games; when time runs out the PC shuts down / locks / logs off. Controls are protected by Windows Hello so the child (9 y.o.) can't stop the timer.

Owner: Dmytro (GitHub: DmitroShapovalov/GameTimer). The app is used at home on the son's PC.

## Start here

| File | What it is |
|---|---|
| `CLAUDE.md` | This file: current state, conventions, gotchas. Read first. |
| `.agent/ROADMAP.md` | Released versions, the **next planned phase**, backlog and known gaps. |
| `.agent/SPEC.md`, `.agent/PLAN.md` | Original v1.0 spec and plan (historical, RU). Implemented; some details superseded. |
| `README.md` | Public user-facing docs (EN): features, shortcuts, build, download. |

## Working agreements

- Talk to the owner in **Russian**. Code, comments, commit messages, README and docs in **English**.
- The agent compiles the code itself (see "Agent environment notes"), but can't run the WPF app: the owner tests behaviour on his Windows PC.
- Commit only when asked ("коммить"). Tags and releases are done by the owner. Feature work goes to a branch and a PR to `master`.
- Keep UI text localized: every user-visible string goes to both `.resx` files (see Localization).
- Before larger features, propose the approach briefly; for UI changes he usually sends screenshots with feedback.

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
  Windows/
    MainWindow             FluentWindow, 4 tabs (Timer, Display, Notifications, Settings), tray, keyboard
    OverlayWindow          Countdown overlay, color zones, pulse animation
    PasswordDialog         Fallback when Windows Hello is unavailable
    ConnectPhoneDialog     QR code with the one-time pairing link, countdown, "connected" state
  Remote/                  Phone UI (plain HTML/CSS/JS, no build step), embedded by LogicalName ("Remote/app.js"):
                           index.html, app.js (EN/RU strings inside), app.css, manifest.webmanifest, icons
installer/setup.iss        Inno Setup script (per-user install, optional autostart)
docs/remote-api.http       Every phone API request and WebSocket message, with examples (REST Client format)
docs/ws-watch.ps1          PowerShell: pair with a code and print /ws events with timestamps
docs/ws-selftest.ps1       PowerShell 5.1/7: end-to-end check of pairing, API and /ws events on the real PC (PASS/FAIL)
.github/workflows/release.yml   On tag v*: publish + build installer + GitHub Release
```

`App` creates `SettingsService`, `AppSettings` and `TimerController` and passes them to `MainWindow`. The window is only a client of `TimerController`: it calls its methods and updates the UI in `OnTimerStateChanged`. Every control surface (window, phone API) must go through the controller, on the UI thread (`Dispatcher.InvokeAsync` from background threads; the controller calls `VerifyAccess`).

## Key behaviours

- **Auth:** `RequireAuth()` in MainWindow: Windows Hello (PIN / fingerprint / face) → fallback `PasswordDialog`. Required for Stop, Reset, changing the expiry action, and Exit while a timer runs.
- **Tray:** closing the window always hides to tray; real exit only from the tray menu. `ShutdownMode=OnExplicitShutdown`.
- **Single instance:** second launch signals the first (`Local\SystemMonitor_7F3A2C1E_*`) and exits; first calls `MainWindow.BringToFront()`.
- **Overlay position:** stored as 0..1 fractions mapped to `[0, screen - overlaySize]`.
- **Keyboard:** Enter = start / add alert, ↑↓ (+Shift) adjust, `:` jumps to minutes, Ctrl+1..4 tabs, Esc = tray.
- **Phone control:** toggle in the Settings tab (auth required) starts/stops `RemoteServer` on `RemotePort` (5123); `RemoteEnabled` is restored at launch without a prompt. `App.OnExit` stops the host. The status line shows the phone URL (`http://<lan-ip>:<port>`).
- **Firewall:** enabling phone control adds the rule first (one UAC prompt) by running this exe with `--configure-firewall <port>`; `App.OnStartup` handles that switch before the single-instance mutex, calls `FirewallHelper.Apply` and exits with its code. netsh output is localized: only the exit code, port and path are parsed.
- **Phone pairing & auth:** "Connect phone" (auth) shows a QR for `http://<lan-ip>:<port>/pair?code=…`. `POST /pair {code, name}` → `{deviceId, name, token}`; the token is 256-bit base64url, stored on the PC only as a SHA-256 hash. Everything under `/api` and `/ws` needs `Authorization: Bearer <token>` (401; 429 after 10 failures/min per IP). `/pair` is limited to 5 POSTs/min per IP. Any paired phone can list / revoke devices; the PC can revoke from the Settings tab (auth).
- **Phone API:** see `docs/remote-api.http`. JSON camelCase, enums as camelCase strings (numbers rejected). Handlers run on the UI thread via `UiThread.InvokeAsync` and only use `TimerController` / `SettingsService`. `PUT /api/settings` is partial and validated, then `SettingsService.NotifyChanged(server)`; `MainWindow` refreshes its controls for any change whose source isn't itself. Color zone logic lives in `AppSettings.GetZone` (overlay and API).
- **Phone UI:** served at `/` (and `/pair?code=…` from the QR) with CSP `script-src 'self'` (no inline scripts/styles), `no-cache`, `nosniff`. Token in `localStorage` (`gametimer.token`). The countdown runs locally between 5 s ticks; language follows the PC setting; on close 1008 `revoked`/`unauthorized` or HTTP 401 it forgets the token and shows the pairing screen. No service worker (not needed for installing; no offline cache).
- **No HTTPS (decided):** the phone UI is plain HTTP on the LAN. Chrome's install prompt needs HTTPS, so the Phones tab shows manual "Add to Home screen" steps (Android / iOS) instead; the `beforeinstallprompt` button only shows up if the UI is ever served over HTTPS. Tailscale (`tailscale serve`) was tried and dropped as too complex for the target users; a snapshot is kept as git tree `794ffcd` (see ROADMAP Phase 2).
- **Extend event order:** `Extended` is raised first, then `TimerService.EvaluateNow()` may raise an alert or the expiry.
- **WebSocket `/ws`:** auth by bearer header or first message `{"type":"auth","token"}` (5 s). Events come from `TimerController` (`StateChanged`, `Tick` throttled to 5 s, `AlertTriggered`, `Extended`), `SettingsService.SettingsChanged` (sent only if the settings DTO changed) and `DeviceStore.Changed` (revoked devices are disconnected). Subscribed while the server runs.
- **Resilience (parental control, opt-in):** a running timer is persisted to `timer-state.json` and resumed on next launch (close / kill / reboot / logoff) by `TimerController.TryResume`; if it expired while gone, the action runs at launch. A resume with leftover state is treated as an unclean close and surfaced as `LastInterruption` (PC InfoBar + phone). "Protection from closing" (Settings, Windows Hello, `AppSettings.TamperProtection`, off by default) calls `ProcessProtection.DenyTermination` to refuse Task Manager / taskkill; an admin token can still override it.
- **Clock:** remaining time is monotonic (`Environment.TickCount64`): system clock changes don't affect it, sleep time still counts. Time-of-day mode reads the wall clock once at start.
- **Debug-only hotkeys:** Ctrl+Shift+Plus / Minus = `Extend(±1)` (temporary, for testing).
- **Validation:** empty time field = 0; inline error text under inputs disables Start.

## Localization

Add every new string to **both** `Strings.resx` and `Strings.ru.resx` (same key), set it in `ApplyLocalization()`. Language switches at runtime without restart. Don't add `<EmbeddedResource Include=...>` for resx: the SDK includes them automatically (NETSDK1022 duplicate error).

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

Release: `git tag vX.Y.Z && git push origin vX.Y.Z` → Actions builds `GameTimerSetup.exe` and attaches it to the GitHub Release. Released so far: v1.0.0, v1.0.1, v1.1.0, v1.2.0, v1.3.0. Bump `<Version>` in the csproj for local builds; CI takes the version from the tag.

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
