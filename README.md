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

## Tech stack

- .NET 8, WPF
- [WPF UI](https://github.com/lepoco/wpfui) 3.0.5 (Fluent Design)
- Windows Hello (`UserConsentVerifier` API)
- Single-file self-contained deployment

## License

MIT
