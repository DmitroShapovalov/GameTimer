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
- **Parent app for Android** — the timer, the weekly schedule, the settings and the history of each PC on your phone, end-to-end encrypted, paired by QR code; notifications when time is up or something looks wrong
- **Control from anywhere** (optional, off by default) — the same app over mobile data or another Wi-Fi, through a relay that can't read anything; no accounts
- **Daily schedule** — an optional per-weekday time budget and allowed-hours window; GameTimer starts the day's allowance automatically and locks when it runs out or the hours end (off by default)
- **Multilingual UI** — English (default), Russian, Dutch and Ukrainian, switchable at runtime
- **Modern look** — Fluent Design (WPF UI) with dark theme

## Download

Download the latest Windows installer and the Android parent app (`.apk`) from the [Releases](../../releases/latest) page.

**Updating.** Just run the new installer while GameTimer is running. Setup asks the app to close; if a
timer is running or the daily schedule is on, the app first asks for Windows Hello (so a child can't
use this to close it). A running timer keeps counting: after the update the new version starts again
by itself and continues where it was. The history shows "Closed for update". (Updating *from* v1.6.0
or older: exit GameTimer from its tray icon first — those versions don't know this signal yet.)

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

## Parent app (Android)

The Android app controls GameTimer on one or more PCs. Install the `.apk` from the release page (Android 8 or newer; allow
installing apps from your browser when Android asks).

**Pairing.** On the PC: **Settings** → turn on **Phone control** (Windows Hello) → **Connect phone**, and scan the QR code
with the app (the **+** button). The phone shows a 6-digit code; type it on the PC and confirm with Windows Hello. The phone
and the PC must be on the same Wi-Fi for pairing. The first phone becomes the **admin** (it can add and remove other
parents); others are **parents**.

In the app, each PC has four pages:

- **Status** — the time left as a ring, when it ends and what happens then; − / + with a step you choose; **Lift
  restrictions** (also for today's schedule); **End at…** and **End now** in the menu. With no timer: start one for a
  duration or until a time of day.
- **Schedule** — the week at a glance; tap a day to set its allowed hours and daily limit, then copy it to weekdays,
  the weekend or every day. Saved to the PC with **Save**.
- **History** — what happened, day by day, with who did it; filter by timer events or alerts.
- **More** — the PC's settings (action at the end, sound alerts, countdown colours, PC language) and the parents.

**Notifications.** The app tells you when time is up, a few minutes before, and when something looks wrong (the timer app
was closed, its settings were tampered with, a new parent was added), also when the app is closed. Choose which ones in
the app's settings (gear on the home screen), where you also pick the app's language.

Everything between the app and the PC is end-to-end encrypted. If the PC gets a new address on your network, the app finds
it again by itself.

## Control from anywhere

Off by default. On the PC: **Settings** → under Phone control, turn on **Control from anywhere (through the internet)**
(Windows Hello). Then the paired phones also reach the PC over mobile data or any other network, through a relay. At home
they still talk directly. Notifications arrive away from home too (Android push).

The relay only passes encrypted messages along: it can't see the timer, names or the history, or send commands. It stores
nothing. What it and Google's push service can see (when and how much, not what) is described in
[`docs/PRIVACY.md`](docs/PRIVACY.md). Turning the option off on the PC disconnects the relay at once.

## Phone web page (older, home Wi-Fi only)

The PC still serves the earlier phone web page for already paired phones: open the address shown under **Phone control**
on the same Wi-Fi. It can start / stop / extend the timer and change settings. New setups should use the Android app.
Developers: the old API is documented in [`docs/remote-api.http`](docs/remote-api.http); the encrypted protocol of the
app in [`docs/protocol.md`](docs/protocol.md).

## Daily schedule

Instead of starting the timer by hand every day, you can set a schedule: for each weekday, a **daily time budget** (in minutes) and the **hours gaming is allowed** (e.g. 15:00–20:00). It is **off by default**.

When it is on, GameTimer works automatically:

- it starts the day's allowance on its own when the PC starts or is unlocked;
- it locks the PC (your chosen action) when the budget runs out or the allowed hours end, and keeps it locked outside those hours;
- a manually started timer is capped to what the schedule still allows.

Edit it on the PC in the **Schedule** tab (`Ctrl`+`6`) — unlock with Windows Hello first — or on the phone under **Schedule**. Both show today's state ("45 min left today", or why gaming is blocked). Because turning the schedule off also needs Windows Hello, it can never lock you, the parent, out by surprise.

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

Phone control is off by default and needs pairing on the PC (QR code, a typed 6-digit code and Windows Hello). The app
and the PC talk end-to-end encrypted (Noise, keys pinned at pairing); the relay for "Control from anywhere" can't read or
change anything. The app can only control the timer, the schedule and the settings — there is no way to run other commands
through it. Threat model and residual risks: [`docs/SECURITY.md`](docs/SECURITY.md); what leaves your home and who sees
what: [`docs/PRIVACY.md`](docs/PRIVACY.md).

## Tech stack

- .NET 8, WPF
- [WPF UI](https://github.com/lepoco/wpfui) 3.0.5 (Fluent Design)
- Windows Hello (`UserConsentVerifier` API)
- ASP.NET Core (embedded Kestrel) for the phone connections; protocol v2 over Noise (libsodium)
- Android parent app: Kotlin, Jetpack Compose, on a Kotlin Multiplatform core shared with the protocol
- Relay: ASP.NET Core on Fly.io; push wake-ups through Firebase Cloud Messaging
- [QRCoder](https://github.com/codebude/QRCoder) for the pairing QR code
- Single-file self-contained deployment

## License

MIT
