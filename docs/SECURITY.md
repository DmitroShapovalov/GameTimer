# GameTimer — security review

Scope: whether phone control (a server the app runs on the home network) can be used to
take control of the PC, plus the local files, the "protection from closing" feature, and dependencies.
This is a review of the code in this repository, not a formal audit.

> Phone control uses only the encrypted protocol v2 (Noise, keys pinned at pairing): at home directly and, with
> "Control from anywhere" on, through a relay that can't read or change anything. Its threat model (what a
> compromised relay, network or lost phone can and can't do) is in [`protocol.md`](protocol.md) (§10.5, §10.6 and
> §11), and what leaves the home and who sees it in [`PRIVACY.md`](PRIVACY.md). The older phone web page and its
> plain-HTTP v1 API (bearer tokens) were **removed in v1.9**; this review covers the PC side.

## What the remote can and cannot do

A paired parent app can do a **fixed, small set of actions**, nothing more:

- start / stop / extend / lift / end the timer;
- read and change timer settings (expiry action, colors, thresholds, alert points, language);
- read and change the daily schedule (per-weekday allowed flag, minute budget 0..1440, allowed-hours window 0..1440);
- list parents, change roles and revoke (admins only);
- read the session history.

The schedule only decides *when* the timer runs and when the normal expiry action fires — its inputs are
validated integer ranges with no path, shell or process argument. The expiry action is one of
**shut down / lock / log off**, run via `shutdown.exe` / `LockWorkStation` with **constant arguments** — there
is no field anywhere that is passed to a shell, a path, or a process argument. So a paired phone can annoy
(shut the PC down, change timer settings) but **cannot run arbitrary code, read files, or reach anything
outside these actions**.

## Attack surface: the embedded server

The server (ASP.NET Core / Kestrel) is the only listening network surface. It is defended in layers:

1. **Off by default.** It listens only after the parent turns on "Phone control" (Windows Hello). With the
   toggle off, nothing listens on the port.
2. **Home network only.** It binds to all interfaces but `NetworkFilter` aborts every connection whose
   remote address is not loopback or a private range (10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10,
   fc00::/7). The Windows Firewall rules we add are also limited to the local subnet.
3. **Two WebSocket endpoints and nothing else.** `/v2` and `/v2/pair` (protocol §3); any other path is 404,
   there are no static files and no HTTP API. A browser request (an `Origin` header) is refused with 403,
   connection slots are limited (503), and frame and buffer sizes are limited by the protocol layer
   (protocol.md §3).
4. **Everything is end-to-end encrypted and authenticated by the protocol.** `/v2` runs Noise IK with the
   phone's key pinned at pairing; an unknown key is closed silently. Nothing on the wire is a bearer
   credential that could be sniffed and replayed.
5. **Pairing needs the PC screen and the parent.** The QR carries a one-time 256-bit secret (2 minutes) in
   the URL fragment; the parent then **types the code the phone shows** on the PC and confirms with Windows
   Hello. Two phones using one QR cancel the pairing (a photographed QR is caught). `/v2/pair` never goes
   through the relay.
6. **Input is validated.** Timer inputs are range-checked; settings are validated (thresholds 1–1440,
   `#RRGGBB` colors, known enums only — numbers are rejected, alert points capped and de-duplicated).

Revoking a parent on the PC (or by an admin phone) closes its sessions at once.

## Residual risks (and how to reduce them)

- **Any device already on the home network can reach the port.** Without a paired key it gets nothing:
  the handshake fails and the connection is closed. It can see that the port is open.
- **The state files are tamper-evident, not tamper-proof.** `settings.json`, `usage.json` and
  `timer-state.json` are encrypted with Windows DPAPI (per-user) via `ProtectedStore`: on disk they are
  opaque, and a hand edit breaks decryption. Every write carries a counter inside the encrypted part, and
  the store keeps the counter of the last write as a mark in the user's registry
  (`HKCU\Software\GameTimer\State`). On load, a file that was **edited, deleted, replaced by an older copy
  (also by its own backup), or planted in plaintext** is noticed: the backup (`<file>.bak`, also encrypted)
  is restored when it is the write just before the mark or newer, and a **"settings tampered"** event is
  written to the history the parent sees on the PC and the phone (the report is kept with the marks until it
  is logged, so killing the app doesn't lose it). What the report can't undo: deleting a file brings back
  its previous version (the settings before the last change), and when no copy decrypts the app starts from
  defaults — today's used time is then lost. Either way the parents are told. Also reported: a copy of the
  app folder run from another place (the marks go by the folder; a real second install on the same account
  pays one report), and a saved timer put back after the timer was stopped. For the two files written every few seconds
  (`usage.json`, `timer-state.json`) a copy up to about a minute old passes unnoticed: after a power cut the
  disk and the registry may disagree by that much. The v2
  identity and paired parents live in `remote-v2.json` (DPAPI); if it is lost or replaced, the PC's key
  changes, every phone has to pair again and a "tampered" event is logged. This is **not** proof against
  someone who runs code as that user, or who also removes the registry marks: DPAPI is reversible for the
  same account. (Until v2.0 a deleted file, or a plaintext one after deleting the backup, went unnoticed.)
- **The clock.** The PC's daily schedule goes by the Windows clock: someone who may change it (an
  administrator account) gets a fresh allowance by moving the date, or into the allowed hours by moving the
  time, and after a reboot a running timer resumes by the clock. Nothing reports it yet (the phone agent
  does, protocol §7.4). A standard account can't change the clock.
- **The app must be running.** It starts from a shortcut in the user's Startup folder. Removing the
  shortcut, disabling it in Task Manager or suspending the process (which "Protection from closing" doesn't
  stop) leaves the PC without limits; the parent app then only shows the PC as offline.
- **The history file is plain text** (`sessions.jsonl`): entries can be removed on the PC. Notifications
  already delivered to the parents' phones are not affected.
- **"Protection from closing" is bypassable by an administrator.** The deny-terminate DACL stops Task
  Manager / `taskkill` only for a standard user. On an administrator account Task Manager elevates and
  overrides it. Use a **standard (non-administrator) Windows account for the child** — then it holds, and
  the timer resume + "closed at HH:MM" notice cover the rest.
- **A compromised or lost phone.** Its key lets whoever holds the unlocked app control the timer until you
  revoke that parent on the PC (the app asks for the phone's screen lock before admin actions).

## Local files

All live next to the exe (per-user install → under the child's/parent's own profile, not shared):

- `settings.json` — settings (no tokens, no passwords). DPAPI-encrypted (+ `.bak`).
- `remote-v2.json` — the PC's protocol key pair and the paired parents' public keys. DPAPI-encrypted.
- `usage.json` — seconds of game time used today, for the daily schedule budget. DPAPI-encrypted (+ `.bak`).
- `timer-state.json` — the running timer, for resume. No secrets. DPAPI-encrypted (+ `.bak`).
- `sessions.jsonl` — the event history (times, event types, phone names). No secrets.
- `diagnostics.log` — unhandled-error text for troubleshooting. No keys or secrets are written to it.

Only `remote-v2.json` holds a key, and only for this Windows account (DPAPI).

## Dependencies

- **ASP.NET Core** (Microsoft, bundled with the .NET 8 runtime) — the embedded server.
- **WPF-UI 3.0.5** — the desktop UI theme.
- **QRCoder 1.8.0** (MIT) — generates the pairing QR **entirely offline**; no network, no image services.

Versions are pinned in `GameTimer.csproj`. The only outbound connection is to the relay, and only while
"Control from anywhere" is on (see `PRIVACY.md`); otherwise the app only serves the LAN and runs local
Windows actions. Releases are built by CI from this repository and published on GitHub; the Android apps are
signed with the project's key, the Windows installer is **not** code-signed (Windows may warn on download).
Third-party CI actions are pinned by commit.

## Recommendations

1. Keep the home Wi-Fi on WPA2/WPA3; don't run phone control on an open network.
2. Give the child a **standard** Windows account (it makes "protection from closing" effective and takes
   away the right to change the clock).
3. Turn phone control **off** when you are not using it.
4. **Revoke** a phone you lose or stop trusting; re-pair with a fresh QR.
5. A fixed PC address (router DHCP reservation) avoids re-pairing and keeps the firewall rule matching.
