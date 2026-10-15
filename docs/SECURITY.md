# GameTimer — security review

Scope: whether the phone remote (an HTTP server the app runs on the home network) can be used to
take control of the PC, plus the local files, the "protection from closing" feature, and dependencies.
This is a review of the code in this repository, not a formal audit.

## What the remote can and cannot do

The embedded server exposes a **fixed, small set of actions**, nothing more:

- start / stop / extend the timer;
- read and change timer settings (expiry action, colors, thresholds, alert points, language);
- list and revoke paired phones;
- read the session history.

The expiry action is one of **shut down / lock / log off**, run via `shutdown.exe` / `LockWorkStation`
with **constant arguments** — there is no field anywhere that is passed to a shell, a path, or a process
argument. So a holder of a valid token can annoy (shut the PC down, change timer settings) but **cannot run
arbitrary code, read files, or reach anything outside these endpoints**. There is no code-execution path.

## Attack surface: the embedded server

The server (ASP.NET Core / Kestrel) is the only network surface. It is defended in layers:

1. **Off by default.** It listens only after the parent turns on "Phone control" (Windows Hello). With the
   toggle off, nothing listens on the port.
2. **Home network only.** It binds to all interfaces but `NetworkFilter` aborts every connection whose
   remote address is not loopback or a private range (10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10,
   fc00::/7). It is **not reachable from the internet** unless the user deliberately port-forwards to it
   (we never ask them to). The Windows Firewall rule we add is also limited to the local subnet.
3. **Pairing requires physical access to the PC screen.** "Connect phone" shows a QR with a one-time code:
   8 characters from a 32-symbol alphabet (~10^12 combinations), valid 2 minutes, single active code,
   consumed on first use. `POST /pair` is rate-limited to 5/min per IP, so the code cannot be guessed in
   its lifetime. Without the code, a device on the LAN cannot pair.
4. **Per-device tokens.** Pairing returns a 256-bit random token. The PC stores only its **SHA-256 hash**,
   never the token. Every `/api` and `/ws` request needs `Authorization: Bearer <token>`; lookup is
   **constant-time** (`CryptographicOperations.FixedTimeEquals`, no early exit). Failed auth is rate-limited
   to 10/min per IP; WebSocket auth has a 5 s window; at most 10 live WebSocket connections.
5. **No CSRF.** Authorization is a bearer header, never a cookie, so a web page the child or parent visits
   cannot make the browser attach credentials to our endpoints. A cross-origin `POST /pair` from a web page
   also triggers a CORS preflight the server does not answer, and still needs the active code.
6. **Input is validated.** Timer inputs are range-checked; settings are validated (thresholds 1–1440,
   `#RRGGBB` colors, known enums only — numbers are rejected, alert points capped and de-duplicated).
   Static files are served by exact path from embedded resources, so there is no path traversal.
7. **Hardening.** Responses carry `Content-Security-Policy` (`script-src 'self'`, no inline), `nosniff`,
   `no-referrer`. The server software header is suppressed, and the request body is capped at 64 KB.

Revoking a phone in Settings (or from another phone) invalidates its token immediately and drops its
open WebSocket.

## Residual risks (and how to reduce them)

- **Plain HTTP on the LAN.** Traffic, including the bearer token, is not encrypted. Someone who can sniff
  your Wi-Fi (already on your network, or on an open/weak access point) could capture a token and replay it
  to control the timer or shut the PC down. This is the accepted v1 trade-off (TLS on a LAN IP needs a
  certificate, which is why HTTPS-via-Tailscale was explored and dropped as too complex). **Mitigation:**
  use WPA2/WPA3 on your home Wi-Fi; treat phone control as a home-network-only convenience; revoke a phone
  you no longer trust.
- **Any device already on the home network can reach the port.** It still needs a valid token to do
  anything; an unpaired device only ever gets 401. A guest or a compromised IoT device cannot pair without
  the on-screen code.
- **`settings.json` is writable by the user.** It holds the device token **hashes** (not tokens) and the
  settings. A program running as the same Windows user could add its own device hash and thereby pair
  itself, or change settings. This requires already running code on the PC as that user, so it is not a
  remote risk. Hardening it (move to `%ProgramData%` with ACLs, or sign it) is tracked in the roadmap.
- **"Protection from closing" is bypassable by an administrator.** The deny-terminate DACL stops Task
  Manager / `taskkill` only for a standard user. On an administrator account Task Manager elevates and
  overrides it. Use a **standard (non-administrator) Windows account for the child** — then it holds, and
  the timer resume + "closed at HH:MM" notice cover the rest.
- **A compromised or lost phone.** Its stored token would let whoever holds it control the timer until you
  revoke that device on the PC.

## Local files

All live next to the exe (per-user install → under the child's/parent's own profile, not shared):

- `settings.json` — settings and device token **hashes** (no tokens, no passwords).
- `timer-state.json` — the running timer, for resume. No secrets.
- `sessions.jsonl` — the event history (times, event types, phone names). No secrets.
- `diagnostics.log` — unhandled-error text for troubleshooting. **Tokens are never written to it** (verified:
  nothing logs the `Authorization` header or a token).

None of these contain a usable credential.

## Dependencies

- **ASP.NET Core** (Microsoft, bundled with the .NET 8 runtime) — the embedded server.
- **WPF-UI 3.0.5** — the desktop UI theme.
- **QRCoder 1.8.0** (MIT) — generates the pairing QR **entirely offline**; no network, no image services.

Versions are pinned in `GameTimer.csproj`. The app makes **no outbound network connections** of its own:
it only serves the LAN and runs local Windows actions. Updates are distributed as a signed GitHub Release
built by CI from this repository.

## Recommendations

1. Keep the home Wi-Fi on WPA2/WPA3; don't run phone control on an open network.
2. Give the child a **standard** Windows account (also makes "protection from closing" effective).
3. Turn phone control **off** when you are not using it.
4. **Revoke** a phone you lose or stop trusting; re-pair with a fresh QR.
5. A fixed PC address (router DHCP reservation) avoids re-pairing and keeps the firewall rule matching.
