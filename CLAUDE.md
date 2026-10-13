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
- Commit only when asked ("коммить"). Push and tags are done by the owner (agent has no GitHub credentials).
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
  Services/
    TimerController.cs     Single source of truth: Start/Stop/Reset/Extend, State, StateChanged; runs the expiry action
    TimerService.cs        DispatcherTimer countdown on a monotonic clock (TickCount64); events Tick, AlertTriggered, TimerExpired
    SettingsService.cs     settings.json next to the exe
    RemoteServer.cs        Embedded Kestrel for the phone remote: Start/Stop, LastError; off by default
    NetworkFilter.cs       Allows only loopback / private LAN remote addresses
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
installer/setup.iss        Inno Setup script (per-user install, optional autostart)
.github/workflows/release.yml   On tag v*: publish + build installer + GitHub Release
```

`App` creates `SettingsService`, `AppSettings` and `TimerController` and passes them to `MainWindow`. The window is only a client of `TimerController`: it calls its methods and updates the UI in `OnTimerStateChanged`. Every control surface (window, phone API) must go through the controller, on the UI thread (`Dispatcher.InvokeAsync` from background threads; the controller calls `VerifyAccess`).

## Key behaviours

- **Auth:** `RequireAuth()` in MainWindow: Windows Hello (PIN / fingerprint / face) → fallback `PasswordDialog`. Required for Stop, Reset, changing the expiry action, and Exit while a timer runs.
- **Tray:** closing the window always hides to tray; real exit only from the tray menu. `ShutdownMode=OnExplicitShutdown`.
- **Single instance:** second launch signals the first (`Local\SystemMonitor_7F3A2C1E_*`) and exits; first calls `MainWindow.BringToFront()`.
- **Overlay position:** stored as 0..1 fractions mapped to `[0, screen - overlaySize]`.
- **Keyboard:** Enter = start / add alert, ↑↓ (+Shift) adjust, `:` jumps to minutes, Ctrl+1..4 tabs, Esc = tray.
- **Phone control:** toggle in the Settings tab (auth required) starts/stops `RemoteServer` on `RemotePort` (5123); `RemoteEnabled` is restored at launch without a prompt. `App.OnExit` stops the host.
- **Clock:** remaining time is monotonic (`Environment.TickCount64`): system clock changes don't affect it, sleep time still counts. Time-of-day mode reads the wall clock once at start.
- **Debug-only hotkeys:** Ctrl+Shift+Plus / Minus = `Extend(±1)` (temporary, for testing).
- **Validation:** empty time field = 0; inline error text under inputs disables Start.

## Localization

Add every new string to **both** `Strings.resx` and `Strings.ru.resx` (same key), set it in `ApplyLocalization()`. Language switches at runtime without restart. Don't add `<EmbeddedResource Include=...>` for resx: the SDK includes them automatically (NETSDK1022 duplicate error).

## Gotchas (compile errors we already hit)

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

Release: `git tag vX.Y.Z && git push origin vX.Y.Z` → Actions builds `GameTimerSetup.exe` and attaches it to the GitHub Release. Released so far: v1.0.0, v1.0.1, v1.1.0.

## Agent environment notes (Cowork)

- The repo lives on the owner's PC at `D:\Projects\GameTimer`, reached through the connected folder (`device_bash`, path `$HOME/mnt/Projects/GameTimer`).
- Git leaves stale `.git/index.lock` / `HEAD.lock` from the sandbox: request delete permission for `D:\Projects`, then `rm -f` them before committing.
- Commit message attribution lines come from the session's system instructions.
- **Building from the agent (compile check).** `device_bash` runs in a Linux VM, not Windows, with network access. Install the .NET 8 SDK into the session home (it does not persist between sessions, ~1 min), then build a copy outside the repo so Linux `bin/obj` never mix with the owner's Windows build:
  ```bash
  curl -sSL https://dot.net/v1/dotnet-install.sh -o ~/di.sh && bash ~/di.sh --channel 8.0 --install-dir ~/.dotnet
  export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
  rm -rf ~/build && mkdir -p ~/build && cd ~/mnt/Projects/GameTimer/src \
    && tar --exclude=bin --exclude=obj -cf - GameTimer | tar -xf - -C ~/build
  cd ~/build/GameTimer && ~/.dotnet/dotnet build -p:EnableWindowsTargeting=true -v q
  ```
  `EnableWindowsTargeting` lets Linux compile `net8.0-windows` (WPF + WinForms). The app can't run there: behaviour is tested by the owner on Windows.
