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
- **Daily schedule** — an optional per-weekday time budget and allowed-hours window; GameTimer starts the day's allowance automatically and locks when it runs out or the hours end (off by default)
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

## Daily schedule

Instead of starting the timer by hand every day, you can set a schedule: for each weekday, a **daily time budget** (in minutes) and the **hours gaming is allowed** (e.g. 15:00–20:00). It is **off by default**.

When it is on, GameTimer works automatically:

- it starts the day's allowance on its own when the PC starts or is unlocked;
- it locks the PC (your chosen action) when the budget runs out or the allowed hours end, and keeps it locked outside those hours;
- a manually started timer is capped to what the schedule still allows.

Edit it on the PC in the **Schedule** tab (`Ctrl`+`6`) — unlock with Windows Hello first — or on the phone under **Settings**. Both show today's state ("45 min left today", or why gaming is blocked). Because turning the schedule off also needs Windows Hello, it can never lock you, the parent, out by surprise.

## Protection from closing

The app can keep the running timer even if it is closed, and can make it hard for the child to close.

- **The timer survives being closed.** If the app is closed, the PC reboots or the user logs off while a timer is running, the timer continues from where it left off the next time the app starts. If the time ran out in the meantime, the action (shutdown / lock / logoff) runs at startup.
- **You are told if it was closed.** Closing the app cleanly goes through the tray and Windows Hello. If it was closed any other way (Task Manager, reboot), the next launch shows "closed at HH:MM" on the PC and on the phone.
- **Prevent closing (optional).** Settings → **Protection from closing** (Windows Hello, off by default) stops the app being ended from Task Manager (`taskkill`, `Stop-Process` too). **This only works if the child uses a standard, non-administrator Windows account** — on an administrator account Task Manager can still close it. The recommended setup is a separate standard account for the child (Settings → Accounts → Family or Other users), which also stops them changing system settings or removing the app from startup.

## Keyboard shortcuts

| Key | Action |
|---|---|
| `Enter` | Start the timer (Timer tab) / add an alert point (Notifications tab) |
| `↑` / `↓` | Increase / decrease the focused time value by 1 |
| `Shift` + `↑` / `↓` | Increase / decrease by 10 |
| `:` | Jump from the hours field to the minutes field |
| `Ctrl` + `1`…`6` | Switch tabs |
| `Esc` | Hide the window to the system tray |

Only one copy of the app can run at a time — launching it again just brings the existing window to the front.

## Security

Phone control is off by default, home-network only, and requires pairing with a one-time QR code; each
phone gets its own token (stored only as a hash). The remote can only start/stop/extend the timer and
change its settings — there is no way to run other commands through it. For the full threat model, the
residual risks (e.g. plain HTTP on the LAN) and recommendations, see [`docs/SECURITY.md`](docs/SECURITY.md).

## Tech stack

- .NET 8, WPF
- [WPF UI](https://github.com/lepoco/wpfui) 3.0.5 (Fluent Design)
- Windows Hello (`UserConsentVerifier` API)
- ASP.NET Core (embedded Kestrel) for the phone remote; plain HTML/CSS/JS phone UI
- [QRCoder](https://github.com/codebude/QRCoder) for the pairing QR code
- Single-file self-contained deployment

## License

MIT
