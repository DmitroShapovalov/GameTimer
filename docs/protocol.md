# GameTimer protocol v2 (hub ↔ agent)

> Status: **draft for review (Phase A, step A1).** This is the contract every module speaks: the
> Flutter parent app (the *hub*), the Windows app (the first *agent*), and later the Android / iOS /
> network agents. Background and decisions: `.agent/VISION.md` (§3 architecture, D1, D2).

The v1 API (`/api/*`, `/ws`, bearer tokens, used by the PWA) stays unchanged next to v2. Nothing in
this document changes v1.

## 1. Goals

- **One contract for all modules.** The hub knows nothing platform-specific; an agent says what it
  can do (§6) and the hub shows only those controls.
- **End-to-end security on every path.** The same encrypted frames go over the home network today and
  through a relay later (Phase B). Whoever carries the bytes — Wi-Fi, router, relay — can neither read
  nor forge them.
- **Readable.** Messages are small JSON documents, so the Dart and C# code that handles them is easy to
  review.

## 2. Layers

```
  JSON message  { "t": "timer.start", "id": 7, ... }          §5–§7
  ─────────────────────────────────────────────────
  Noise transport message (ChaCha20-Poly1305)                 §4
  ─────────────────────────────────────────────────
  WebSocket binary message  ws://<host>:<port>/v2             §3
```

## 3. Transport

- The agent listens on its existing port (default `5123`), path **`/v2`**, WebSocket.
  Same LAN-only filter as v1 (loopback / private ranges only).
- Plain `ws://` is intentional: confidentiality and authenticity come from Noise (§4), not from TLS.
- **One WebSocket binary message = one Noise message.** Text messages are a protocol error.
- Max message size **65 535 bytes** (the Noise limit). Larger → close.
- Any decryption failure, unexpected message or malformed JSON → the side that noticed **closes the
  socket** without saying why (no error oracle). Handshake failures count toward the same per-IP limit
  as v1 auth failures (10 / min).

## 4. Security: Noise

**Protocol name:** `Noise_IK_25519_ChaChaPoly_BLAKE2b`
**Prologue:** the ASCII bytes `GameTimer/2` (binds the handshake to this protocol).

> **Correction to D2 (KK → IK).** While writing this spec it turned out that `KK` doesn't fit a family
> with more than one parent phone: in KK the agent must know *before* the first message which parent
> is calling, so the parent would have to announce itself in clear text first. `IK` solves this: the
> parent's static key travels **encrypted inside the first message**, the agent decrypts it and checks
> it against its list of paired parents. Same guarantees as KK — mutual authentication, forward secrecy,
> replay protection — and it is the pattern WireGuard uses. It also makes pairing (§8) use the very same
> handshake. D2 is updated accordingly.

Roles: the **hub is always the initiator**, the **agent is always the responder**.

```
  hub → agent   msg 1:  e, es, s, ss   + encrypted payload   (hub's static key travels encrypted)
  agent → hub   msg 2:  e, ee, se      + encrypted payload
  then:         transport messages both ways
```

- The hub knows the agent's static public key from pairing (the QR, §8). It refuses to talk to any
  other key — this is what stops a fake "PC" on the network.
- The agent learns the hub's static key from message 1 and accepts the session only if that key is in
  its **paired-hubs list** — or if the payload carries a valid pairing code (§8).
- Message 1 payload: `{ "t": "connect", "app": "<name/version>" }` or `{ "t": "pair", ... }` (§8).
  Message 2 payload: empty.
- Transport nonces are the Noise counters (sequential; WebSocket keeps order). No rekeying in v2:
  sessions are short-lived and the counter space is 2⁶⁴.

**Keys and storage**
- Each agent and each hub install has one **static X25519 key pair**, generated on first run.
  - Windows agent: private key in `ProtectedStore` (DPAPI, per-user).
  - Phones: Android Keystore / iOS Keychain (via `flutter_secure_storage`).
- **Agent id** = first 10 bytes of `BLAKE2b(agentPublicKey)`, base32 (16 characters). Used for
  discovery and in the UI; not secret.
- Private keys never leave the device.

**Implementations and tests**
- Dart (`packages/gt_protocol`) and C# (`src/GameTimer.Protocol`), both strictly following the Noise
  spec on top of libsodium primitives.
- Both must pass the **official Noise test vectors** for this protocol name, and CI runs a **live Dart ↔
  C# handshake + encrypted round-trip**. A change that breaks either fails the build.

## 5. Message envelope (inside Noise)

UTF-8 JSON object. Common fields:

| Field | Meaning |
|---|---|
| `t` | Message type, e.g. `timer.start`, `result`, `ev.state` |
| `id` | Request id (integer, chosen by the hub, unique per session). Only on requests |
| `re` | On a `result` / `error`: the `id` it answers |

Three kinds:

```jsonc
// request (hub → agent)
{ "t": "timer.extend", "id": 12, "minutes": 15 }
// response (agent → hub): exactly one per request
{ "t": "result", "re": 12, "state": { ... } }
{ "t": "error",  "re": 12, "code": "not_running" }
// event (agent → hub, unsolicited): type starts with "ev."
{ "t": "ev.alert", "minutes": 5 }
```

Rules:
- **Unknown fields are ignored** (forward compatibility). An unknown request type → `error`
  `unknown_command`. A request for a capability the agent didn't announce → `error` `unsupported`.
- Times are ISO-8601 with offset (`2026-10-18T20:30:00+02:00`); durations in **seconds** unless the
  field name says `minutes`. Enum values are camelCase strings, same as v1.

## 6. Session start and capabilities

Right after the handshake the agent sends **`hello`**, unprompted:

```jsonc
{
  "t": "hello",
  "protocol": 2,
  "agent": { "id": "K7Q2…", "name": "Kids PC", "platform": "windows", "version": "1.7.0" },
  "capabilities": ["timer", "schedule", "settings", "history", "peers"],
  "state": { /* §7.1 */ }
}
```

**Capabilities** are the heart of the module model: the hub renders a control only if the agent lists
the matching capability. Names are stable; a breaking change gets a new name (`timer2`).

| Capability | What it unlocks | Windows agent (A3) |
|---|---|---|
| `timer` | start / stop / extend a countdown, live state | ✓ |
| `schedule` | daily schedule (budget + allowed hours per weekday) | ✓ |
| `settings` | expiry action, warning thresholds, sound alerts, colors, language | ✓ |
| `history` | session event log | ✓ |
| `peers` | list / revoke paired parent devices | ✓ |
| `apps.usage` | per-app usage report | — (future: Android) |
| `apps.block` | block / allow specific apps | — (future: Android, iOS) |
| `lock` | lock the device now | — (future) |

Future agents simply announce a different list; the hub needs no change for a capability it already
knows.

## 7. Requests and events

Payloads reuse the shapes of the v1 API (`docs/remote-api.http`), so the Windows agent maps them
one-to-one onto `TimerController` / `SettingsService`.

### 7.1 State

`state` object (in `hello`, in many `result`s and in `ev.state`):

```jsonc
{
  "timer": { "state": "running", "mode": "interval", "remainingSeconds": 1834,
             "targetTime": "2026-10-18T20:30:00+02:00", "action": "lock", "zone": "green" },
  "schedule": { "status": "allowed", "availableSeconds": 2700, "usedSecondsToday": 1800 },
  "interruption": null            // or ISO time of the last unclean close (v1 "lastInterruption")
}
```

### 7.2 Requests (hub → agent)

| Type | Capability | Body | Result |
|---|---|---|---|
| `state.get` | — | — | `state` |
| `timer.start` | `timer` | `mode` (`interval`/`absolute`), `hours`, `minutes` | `state` |
| `timer.stop` | `timer` | — | `state` |
| `timer.extend` | `timer` | `minutes` (±) | `state` |
| `interruption.ack` | `timer` | — | `state` |
| `settings.get` | `settings` | — | `settings` |
| `settings.put` | `settings` | any subset of the v1 settings fields | `settings` |
| `schedule.get` | `schedule` | — | `schedule` (`enabled`, `week[7]`, as v1) |
| `schedule.put` | `schedule` | full `schedule` | `schedule` |
| `history.get` | `history` | `limit` (default 200) | `events[]` (newest first, as v1) |
| `peers.list` | `peers` | — | `peers[]`: `id`, `name`, `pairedAt`, `lastSeen`, `current` |
| `peers.revoke` | `peers` | `id` | `peers[]` (the revoked peer's session is closed at once) |

Error `code`s: the v1 set (`invalid_time`, `already_running`, `not_running`, `invalid_minutes`,
`invalid_threshold`, `invalid_alerts`, `invalid_color`, `invalid_language`, `invalid_schedule`) plus
`bad_request`, `unknown_command`, `unsupported`.

### 7.3 Events (agent → hub)

| Type | When | Body |
|---|---|---|
| `ev.state` | timer started / stopped / expired / extended, schedule allowed↔blocked, interruption changed | `state` |
| `ev.tick` | every **30 s** while a timer runs (the hub counts down locally in between) | `state` |
| `ev.alert` | a sound-alert point was reached | `minutes` |
| `ev.settings` | settings changed (by the PC or another parent) | `settings` |
| `ev.history` | a history event was recorded | `event` (one entry) |
| `ev.peers` | a parent device was paired or revoked | `peers[]` |

## 8. Pairing v2

Goal: the parent's phone and the PC exchange public keys **once**, with the PC's owner present.

1. On the PC: Settings → **Connect phone** (Windows Hello). The PC shows a QR with a one-time code
   (valid 2 min, single use — exactly as v1).
2. **One QR serves both the browser and the app:**
   `http://<lan-ip>:<port>/pair?code=<code>&k=<agent public key, base64url>&n=<PC name>`
   - A phone camera opens it in the browser → the v1 PWA pairing (it ignores `k`).
   - The parent app's in-app scanner reads `k` and pairs over v2.
3. The app runs the IK handshake to the key `k` with message-1 payload
   `{ "t": "pair", "code": "<code>", "name": "<phone name>" }`.
4. The agent checks the code (constant-time, single use, rate-limited), stores the hub's static public
   key + name in its paired-hubs list, and replies with `hello`. The app stores `{agent id, public key,
   last host, port}`.
5. From then on every connection is a normal IK handshake with `{ "t": "connect" }`.

Revoking a parent = removing its public key from the agent (PC Settings, or `peers.revoke` from any
parent); its next handshake fails. The app can likewise "forget" a PC.

Why this is safe: someone else on the Wi-Fi can't pair without the code shown on the PC screen, can't
impersonate the PC (the app pins `k` from the QR), and can't read or replay sessions (Noise).

## 9. Discovery (step A6)

The agent announces `_gametimer._tcp` over mDNS with TXT `id=<agent id>`, `v=2`. The app connects to
the last known host first; if that fails, it browses for the agent id and updates the stored host. The
QR's IP is only the first hint. (Discovery only helps *find* the PC; trust always comes from the key.)

## 10. Out of scope for v2 (later phases)

- The relay (Phase B): it will carry exactly these WebSocket frames unchanged; only addressing is added.
- Push wake-ups (Phase B).
- `apps.*` and `lock` capabilities (agents of Phases C–D).
