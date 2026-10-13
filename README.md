# GameTimer

A game time limiter for Windows — designed for parents who want to manage their children's gaming sessions with a transparent, always-on-top countdown overlay.

## Features

- **Overlay timer** — a color-coded countdown displayed on top of any game (including borderless fullscreen)
- **Two timer modes** — set a duration (e.g. 1 hour 30 min) or a specific time of day (e.g. 20:00)
- **Color zones** — green → yellow → red as time runs out, with configurable thresholds and colors
- **Sound alerts** — configurable notification points (e.g. 10 min, 2 min, 1 min before timer ends)
- **Auto-action on expiry** — shut down, lock screen, or log off when time is up
- **Parental protection** — all controls are protected by Windows Hello (PIN / fingerprint / face recognition) with Windows password fallback
- **System tray** — minimizes to tray on close; exit only from the tray menu (also protected)
- **Draggable overlay position** — position the timer anywhere on screen via a visual preview
- **Phone control over home Wi-Fi** — see and control the timer from a phone (start, stop, ±15 min, settings); no cloud, no app store, paired by QR code
- **Bilingual UI** — English (default) and Russian, switchable at runtime
- **Modern look** — Fluent Design (WPF UI) with dark theme

## Download

Download the latest installer from the [Releases](../../releases/latest) page.

## Building from source

### Prerequisites

- [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0) (targeting `net8.0-windows10.0.17763.0`)
- Windows 10 (build 17763) or later

### Build

```bash
cd src/GameTimer
dotnet build -c Release
```

### Publish (single-file)

```bash
cd src/GameTimer
dotnet publish -c Release -r win-x64 --self-contained
```

The output will be in `bin/Release/net8.0-windows10.0.17763.0/win-x64/publish/`.

## How it works

1. Launch the app and set the desired game time (interval or specific time)
2. Click **Start** — the overlay appears on top of all windows
3. The timer counts down with color-coded display (green → yellow → red)
4. Sound alerts play at configured time points
5. When the timer expires, the configured action executes (shutdown / lock / logoff)
6. To stop or modify the timer, Windows Hello or Windows password authentication is required

## Phone control (home Wi-Fi)

The PC app can serve a small web app to your phone. It works only inside your home network and is **off by default**.

1. In **Settings**, turn on **Phone control** (Windows Hello). The first time, Windows asks for administrator rights once: GameTimer adds a firewall rule that lets only devices on your home network reach it. If you skip that, a warning with an **Allow in firewall** button stays under the switch.
2. Click **Connect phone** (Windows Hello) and scan the QR code with the phone camera. The phone must be on the same Wi-Fi. The code is valid for 2 minutes and works once.
3. Give the phone a name and tap **Connect**.

On the phone you can now:

- see the countdown in the same colors as on the PC, with the end time and what happens then;
- start a timer (duration or time of day), stop it, add or remove 15 minutes;
- change the expiry action, colors and thresholds, sound alerts and the language;
- see and remove paired phones.

Changes made on the PC show up on the phone immediately, and the other way round.

**Add it to the home screen** (once): Android — Chrome menu ⋮ → *Add to Home screen*; iPhone — Safari → *Share* → *Add to Home Screen*.

**Security.** Only paired phones can control the timer: each gets its own random token, and the PC stores only a hash of it. Requests from outside the home network are refused, and repeated failed attempts are rate-limited. Remove a phone in **Settings** (or from another phone) to cut it off at once. The connection is plain HTTP inside your home network.

**If the phone shows "PC unreachable":** check that the phone is on the same Wi-Fi and that the PC is on. The address the phone uses is shown under the **Phone control** switch (e.g. `http://192.168.1.20:5123`). If the router gave the PC a new address, connect the phone again with a new QR code and add the new page to the home screen; to avoid this, reserve a fixed address for the PC in the router (DHCP reservation).

Developers: the API and WebSocket messages are documented in [`docs/remote-api.http`](docs/remote-api.http).

## Keyboard shortcuts

| Key | Action |
|---|---|
| `Enter` | Start the timer (Timer tab) / add an alert point (Notifications tab) |
| `↑` / `↓` | Increase / decrease the focused time value by 1 |
| `Shift` + `↑` / `↓` | Increase / decrease by 10 |
| `:` | Jump from the hours field to the minutes field |
| `Ctrl` + `1`…`4` | Switch tabs |
| `Esc` | Hide the window to the system tray |

Only one copy of the app can run at a time — launching it again just brings the existing window to the front.

## Tech stack

- .NET 8, WPF
- [WPF UI](https://github.com/lepoco/wpfui) 3.0.5 (Fluent Design)
- Windows Hello (`UserConsentVerifier` API)
- ASP.NET Core (embedded Kestrel) for the phone remote; plain HTML/CSS/JS phone UI
- [QRCoder](https://github.com/codebude/QRCoder) for the pairing QR code
- Single-file self-contained deployment

## License

MIT
