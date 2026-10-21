# GameTimer protocol v2 (hub ↔ agent)

> Status: **draft 3 for approval (Phase A, step A1).** The contract every module speaks: the native
> parent app (the *hub*), the Windows app (the first *agent*), and later the Android / iOS / network
> agents. Background and decisions: `.agent/VISION.md` (§3 architecture, decisions D2–D7).
> Drafts 2 and 3 address two rounds of independent review — see §13.

The v1 API (`/api/*`, `/ws`, bearer tokens, used by the PWA) stays unchanged next to v2.

Key words **must / must not / should** are normative. Sections marked *non-normative* describe
recommended app behaviour that the other side cannot check.

## 1. Goals

- **One contract for all modules.** The hub knows nothing platform-specific; an agent says what it
  can do (§6) and the hub shows only those controls.
- **End-to-end security on every path.** The same encrypted messages travel over the home network
  today and through a relay later (§10). Whoever carries the bytes can neither read nor forge them.
- **Readable.** Messages are small JSON documents, easy to review in Kotlin, Swift and C#.

## 2. Layers

```
  JSON message  { "t": "timer.extend", "id": 7, "rid": "…", "minutes": 15 }   §5–§7
  ──────────────────────────────────────────────────────────────────────────
  Noise transport message (ChaCha20-Poly1305)                                  §4
  ──────────────────────────────────────────────────────────────────────────
  WebSocket binary message  ws://<host>:<port>/v2   (or /v2/pair)              §3
```

## 3. Transport

**Endpoints** on the agent's existing port (default `5123`), same LAN-only filter as v1:

| Path | Use | Noise pattern |
|---|---|---|
| `/v2` | normal sessions | `Noise_IK_25519_ChaChaPoly_BLAKE2b` |
| `/v2/pair` | pairing a new hub | `Noise_IKpsk1_25519_ChaChaPoly_BLAKE2b` |

- The **major** protocol version is chosen by the path (`/v3` later). An agent without a path answers
  the upgrade with HTTP 404 — that is how a hub learns "this agent doesn't speak that version". Minor
  versions are negotiated inside the session (§6).
- Plain `ws://` is intentional: confidentiality and authenticity come from Noise (§4).
- **One WebSocket binary message = one Noise message.** A text message is a protocol error.
- **Sizes:** a Noise message is at most 65 535 bytes, so a JSON plaintext is at most **65 519 bytes**
  (16-byte tag). Larger → protocol error.
- **Browsers are refused:** an upgrade request carrying an `Origin` header **must** be rejected
  (403). The hub app never sends one. This stops web pages (on the PC or a phone) from opening v2
  sockets or burning rate limits.

**Limits and timeouts (agent side)**

| Limit | Value |
|---|---|
| Handshake must complete (msg 1 → `ready`, §4.3) | 5 s, else close |
| Connections waiting for a handshake | 8 in total, at most 2 per remote IP; extra upgrades get 503 |
| Live sessions per hub / in total | 2 / 16 (a new *confirmed* session beyond 2 closes that hub's oldest) |
| Idle | the hub sends an encrypted `ping` every 30 s; either side closes after 90 s of silence |
| Session lifetime | 24 h; then the agent sends `bye` `{ "code": "expired" }` and the hub reconnects |
| Failed handshakes per remote IP | 10 / min (own counter, **not** shared with v1); never applied to a handshake that authenticates a known hub key |

**Errors**
- **Before** the handshake is confirmed (§4.3): any failure → close silently (no oracle).
- **After**: send `{ "t": "bye", "code": "<reason>" }` (§5), then close. Reasons: `protocol_error`,
  `revoked`, `expired`, `denied`, `code_used`, `busy`, `shutdown`.

## 4. Security

### 4.1 Patterns, roles, prologue

- Roles are fixed: the **hub is always the initiator**, the **agent is always the responder**.
- Prologue: the ASCII bytes `GameTimer/2` for both patterns (fixed for the whole major version).

```
  /v2       IK:       → e, es, s, ss   (payload)    ← e, ee, se   (payload: empty)
  /v2/pair  IKpsk1:   → e, es, s, ss, psk   (payload)    ← e, ee, se   (payload: empty)
  then: transport messages both ways
```

- The hub knows the agent's static public key from pairing (the QR) and **must** refuse any other
  key — this stops a fake "PC" on the network.
- On `/v2` the agent decrypts the hub's static key from msg 1 and **must** reject the handshake unless
  that key is in its paired-hubs list. On `/v2/pair` it accepts any hub key, but only if the PSK (the
  one-time pairing secret) matches, and then only after the PC owner confirms (§8). With `psk1` the
  PSK is mixed in at the end of msg 1, so a wrong secret really does fail the msg 1 MAC. (`psk2`
  would only mix it in at msg 2, letting anyone who knows the agent's public key send a valid msg 1.)

### 4.2 What msg 1 may carry

Per the Noise spec, the msg 1 payload of IK is encrypted to the agent but **can be replayed**, its
sender authentication is open to key-compromise impersonation, and it has **no forward secrecy**
against a later compromise of the agent's static key. The msg 2 payload and all transport messages
have the full guarantees. Therefore:

- msg 1 payload carries **only** routing / replay data, nothing sensitive and nothing that changes
  state:
  - `/v2`: `{ "t": "connect" }`
  - `/v2/pair`: `{ "t": "pair" }`
- msg 2 payload is **empty**.
- Names, versions and everything else are sent only after the handshake (§4.3, §6, §8).

**Replays.** A replayed msg 1 can never become a session: the replayer can't produce the first
transport message (§4.3). It only occupies a handshake slot for up to 5 s, bounded by the limits in
§3. (Draft 2 added a `ts` counter on top; it was dropped because it could lock a real phone out —
restore from backup, wrong clock — without adding any security beyond the `ready` rule.)

### 4.3 Key confirmation: the `ready` rule

After msg 2 the agent knows only that *someone* sent a valid msg 1. It **must not** treat the session
as real — no state change, no `lastSeen` update, no closing of an older session, no pairing commit,
no `hello` — until it has received and decrypted the **first transport message**, which the hub
**must** send immediately and which **must** be:

```jsonc
{ "t": "ready", "protocol": 2, "minor": 0, "app": "GameTimer Parent 0.1.0 (android)" }
```

On `/v2/pair` the first transport message is `pair.hello` instead (§8). Anything else first →
protocol error.

### 4.4 Keys, identifiers, storage

- Each agent install and each hub install has one **static X25519 key pair**, generated on first
  run with a CSPRNG. Private keys never leave the device.
  - Windows agent: in `ProtectedStore` (DPAPI, per-user). See the assumption in §11.
  - Phones: Android Keystore / iOS Keychain, stored **device-only**:
    not synced and excluded from backups (Keychain `…ThisDeviceOnly`, non-exportable Keystore key),
    so one hub key never ends up on two devices.
- **Ids** (not secret, used in the UI, lists and logs):
  `id = base32( BLAKE2b(outlen = 32, key = none, input = TAG ‖ publicKey)[0..10) )`
  with `TAG` = ASCII `GameTimer agent id v2` (agents) or `GameTimer hub id v2` (hubs).
  10 bytes → **16 characters**. The hub **must** compute the agent id from the pinned key and check
  it against `hello.agent.id`.
- **Per-hub secret `ak`**: 32 random bytes the agent creates **for each hub** at pairing and shares
  only with that hub (inside the encrypted `pair.result`, repeated in every `hello`). Used only to
  derive that hub's rotating tags: discovery (§9) and, in Phase B, relay routing tokens (§10).
  Revoking a hub deletes its `ak`; no other hub's key changes, so a parent who is away from home at
  that moment keeps working.

### 4.5 Encodings (both implementations must match exactly)

- **base64url**: RFC 4648 §5, **no padding**.
- **base32**: RFC 4648 alphabet, **uppercase, no padding**.
- **JSON**: UTF-8, I-JSON (RFC 7493): no duplicate keys (→ protocol error), integers within
  ±(2⁵³ − 1).
- **Times**: ISO-8601 with offset. **Durations** are seconds unless the field is named `minutes` /
  `hours` or ends in `Minutes`.
- 8 bytes in base32 without padding = **13 characters**; 10 bytes = **16 characters**.
- **Noise implementation notes** (all caught by the test vectors): HMAC is the Noise HMAC built on
  BLAKE2b (not libsodium's keyed `crypto_generichash`); the ChaCha20-Poly1305 nonce is 4 zero bytes
  followed by the 64-bit **little-endian** counter; an all-zero X25519 result **must** abort the
  handshake; in psk patterns the `e` token also calls `MixKey(e)`.

### 4.6 Implementations and tests

- Kotlin Multiplatform: `shared/` (Android and iOS). C#: `src/GameTimer.Protocol`. Both implement the Noise state machine
  strictly per the spec (rev 34) on libsodium primitives — no custom constructions.
- Both **must** pass published test vectors for both protocol names (the *cacophony* vector set;
  *snow* as a cross-check). Whether a published set covers `IKpsk1` with 25519 / ChaChaPoly / BLAKE2b
  is checked first thing in A2; if none does, vectors are generated with an independent
  implementation (e.g. `snow`) and committed to the repo.
- CI runs a **live Kotlin ↔ C# test**: pairing, IK handshake with `ready`, an encrypted request/response
  round-trip, and a replayed msg 1 that never turns into a session.

## 5. Message envelope (inside Noise)

| Field | On | Meaning |
|---|---|---|
| `t` | all | message type |
| `id` | requests | positive integer, **strictly increasing** per session; a duplicate or decreasing `id` → `bye` `protocol_error` |
| `rid` | state-changing requests | idempotency key: 16 random bytes, base64url. **Required** where §7.2 says so |
| `re` | `result`, `error` | the `id` answered |

```jsonc
{ "t": "timer.extend", "id": 12, "rid": "q3v…", "minutes": 15 }         // request (hub → agent)
{ "t": "result", "re": 12, "state": { … } }                              // exactly one reply per request
{ "t": "error",  "re": 12, "code": "not_running" }
{ "t": "ev.alert", "minutes": 5 }                                        // event (agent → hub), "ev." prefix
{ "t": "bye", "code": "revoked" }                                        // either side, then close
{ "t": "ping" }  /  { "t": "pong" }                                      // keepalive (hub pings, agent pongs)
```

Rules:
- **Unknown fields are ignored.** Unknown request type → `error` `unknown_command`. Request for a
  capability not announced → `error` `unsupported`. Not allowed for this hub's role → `error`
  `forbidden`.
- **At most 16 outstanding requests** per session; more → `error` `busy`.
- **Idempotency.** For each hub the agent keeps a cache: `rid` → (hash of the request body, reply).
  - Entries live **10 minutes**, at most **256 per hub** (oldest dropped first), and are **persisted**
    in `ProtectedStore`, so a PC restart doesn't make a resent `timer.extend` run twice.
  - A known `rid` with the **same** body is not executed again; the stored reply is returned. A known
    `rid` with a **different** body → `error` `bad_request`.
  - `error` replies are cached too, except `busy`.
  - A hub's entries are deleted when it is revoked.
  - The hub stops resending after **5 minutes** (well inside the 10-minute window) and asks the user.
- **Concurrency.** `settings` and `schedule` carry a `rev` (integer, +1 on every change). A write may
  include `ifRev`; if it doesn't match → `error` `conflict` with the current value in `value`.
- **Ordering.** For a successful state-changing request the agent sends the `result` to the
  requesting session first, then the matching `ev.*` to **all other** sessions (not back to the
  requester).

## 6. Session start: `ready` → `hello`

1. Hub sends `ready` (§4.3) with its `protocol` major and highest supported `minor`.
2. Agent answers with **`hello`**:

```jsonc
{
  "t": "hello",
  "protocol": 2, "minor": 0,                 // the minor both will use = min(hub, agent)
  "agent": { "id": "K7Q2…", "name": "Kids PC", "platform": "windows", "version": "1.7.0" },
  "you": { "id": "H4XM…", "role": "admin" }, // the hub's own id and role on this agent
  "capabilities": ["timer", "schedule", "settings", "history", "peers"],
  "ak": "<base64url>",                        // this hub's own secret (§4.4)
  "state": { /* §7.1 */ }
}
```

The agent **must not** close a session because of a newer minor; it answers with the minor it
supports and the hub adapts.

**Capabilities** — the hub renders a control only if the agent lists the capability. Names are
stable; a breaking change gets a new name (`timer2`).

| Capability | What it unlocks | Windows agent (A3) |
|---|---|---|
| `timer` | start / stop / extend a countdown, live state | ✓ |
| `schedule` | daily schedule (budget + allowed hours per weekday) | ✓ |
| `settings` | expiry action, warning thresholds, sound alerts, colors, language | ✓ |
| `history` | session event log | ✓ |
| `peers` | list paired parents, roles, revoke | ✓ |
| `apps.usage` | per-app usage report | — (future: Android) |
| `apps.block` | block / allow specific apps | — (future: Android, iOS) |
| `lock` | lock the device now | — (future) |

## 7. Requests and events

Payloads reuse the shapes of the v1 API (`docs/remote-api.http`), so the Windows agent maps them onto
`TimerController` / `SettingsService` one-to-one.

### 7.1 State

```jsonc
{
  "now": "2026-10-18T20:00:00+02:00",   // agent's clock when the state was produced
  "timer": { "state": "running", "mode": "interval", "remainingSeconds": 1834,
             "targetTime": "2026-10-18T20:30:00+02:00", "action": "lock", "zone": "green" },
  "schedule": { "status": "allowed", "availableSeconds": 2700, "usedSecondsToday": 1800 },
  "interruption": null                  // or ISO time of the last unclean close
}
```

The hub counts down from `remainingSeconds` using **its own monotonic clock** from the moment the
message arrived. `targetTime` and `now` are for display only: the PC clock may be wrong or changed.

### 7.2 Requests (hub → agent)

| Type | Cap. | `rid` | Role | Body | Result |
|---|---|---|---|---|---|
| `state.get` | — | — | any | — | `state` |
| `timer.start` | `timer` | ✓ | any | `mode`; for `interval`: `hours` + `minutes` = duration; for `absolute`: `hours` + `minutes` = local time of day on the agent | `state` |
| `timer.stop` | `timer` | ✓ | any | — | `state` |
| `timer.extend` | `timer` | ✓ | any | `minutes` (±) | `state` |
| `interruption.ack` | `timer` | ✓ | any | — | `state` |
| `settings.get` | `settings` | — | any | — | `settings` (incl. `rev`) |
| `settings.put` | `settings` | ✓ | any | any subset of the v1 settings fields, optional `ifRev` | `settings` |
| `schedule.get` | `schedule` | — | any | — | `schedule` (`enabled`, `week[7]`, `rev`) |
| `schedule.put` | `schedule` | ✓ | any | full schedule, optional `ifRev` | `schedule` |
| `history.get` | `history` | — | any | `limit` (1–100, default 50), optional `before` (cursor) | `events[]` newest first, `more`, `next` (cursor) |
| `peers.list` | `peers` | — | any | — | `peers[]` |
| `peers.revoke` | `peers` | ✓ | admin (or self) | `id` | `peers[]` |
| `peers.setRole` | `peers` | ✓ | admin | `id`, `role` (`admin` / `parent`) | `peers[]` |

`peers[]` entries: `id`, `name`, `role`, `pairedAt`, `lastSeen`, `current` (= this is the hub making
the request).

Error `code`s: the v1 set (`invalid_time`, `already_running`, `not_running`, `invalid_minutes`,
`invalid_threshold`, `invalid_alerts`, `invalid_color`, `invalid_language`, `invalid_schedule`) plus
`bad_request`, `unknown_command`, `unsupported`, `forbidden`, `conflict`, `busy`, `last_admin`.

### 7.3 Events (agent → hub)

| Type | When | Body |
|---|---|---|
| `ev.state` | timer started / stopped / expired / extended; schedule allowed ↔ blocked; interruption changed | `state` |
| `ev.tick` | every **30 s** while a timer runs | `state` |
| `ev.alert` | a sound-alert point was reached | `minutes` |
| `ev.settings` | settings changed (on the PC or by another parent) | `settings` |
| `ev.schedule` | schedule changed | `schedule` |
| `ev.history` | a history event was recorded | `event` |
| `ev.peers` | a parent was added, revoked or changed role | `peers[]`, `change` (`added` / `revoked` / `role`), `id` |

## 8. Roles and pairing

### 8.1 Roles

- **admin** — everything, plus `peers.revoke` of other parents and `peers.setRole`.
- **parent** — all daily control (timer, settings, schedule, history), can revoke only itself.
- The **first** hub paired to an agent with no admin becomes **admin**; later ones become **parent**.
  An admin can make another parent admin. A hub request to remove or demote the **last admin** is
  refused (`last_admin`).
- On the PC, the owner (behind Windows Hello) can always see, revoke and change the role of any hub —
  including removing the last admin (e.g. a lost phone). The next hub paired then becomes admin.
- Revoking a hub closes its live sessions at once (`bye` `revoked`), deletes its `ak` and cached
  replies, and notifies everyone (`ev.peers`).

### 8.2 Pairing flow

1. On the PC: Settings → **Connect phone** (Windows Hello). The PC creates, independently:
   - the v1 code (for the PWA, as today), and
   - a **pairing secret** `s`: 32 random bytes, one use, valid 2 minutes.
2. **One QR, two independent secrets.** The v2 data is in the URL **fragment**, which browsers never
   send to the server:
   `http://<lan-ip>:<port>/pair?code=<v1 code>#v=2&k=<agent public key>&s=<secret>&n=<PC name>`
   (`k`, `s` base64url; `n` URL-encoded). A phone camera opens the PWA pairing (v1 uses only `code`);
   the app's scanner uses only the fragment. Neither secret can stand in for the other.
3. The app connects to `/v2/pair` and runs **IKpsk1** with `rs = k`, `psk = s`; msg 1 payload
   `{ "t": "pair" }`. A wrong `s` fails the msg 1 MAC → silent close. A *valid* msg 1 does **not**
   use up `s`: it could be a replay of one sniffed earlier.
4. First transport message from the app:
   `{ "t": "pair.hello", "protocol": 2, "minor": 0, "name": "Dmytro's phone", "app": "…" }`.
   Only now has a live phone proven `s`; the agent marks `s` as used. Up to 3 pairing attempts may be
   in progress at once.
   - `s` stays valid **for checking msg 1** until its 2-minute window ends, even after it is used. A
     later phone that knows `s` can therefore complete the handshake (that proves it has the QR) and
     reach `pair.hello`.
   - If a second, **different** hub key reaches `pair.hello` with the same `s` — at the same time or
     after the first — the agent sends it `bye` `code_used`, cancels any pairing still waiting for
     confirmation (`bye` `denied` to that phone), and the PC shows *"Two phones tried to connect with
     this code — it may have been photographed. Start again."*
5. A **6-digit confirmation code** is derived from the final handshake hash `h` (Noise
   `GetHandshakeHash()` after msg 2):
   `uint32_be( BLAKE2b(outlen = 32, input = "GameTimer pair code v2" ‖ h)[0..4) ) mod 1 000 000`,
   zero-padded. **The phone shows the code; the PC asks the owner to type it in:** *"'Dmytro's
   phone' wants to connect. Enter the code shown on your phone."* Typing forces a real comparison,
   which a glance at two numbers doesn't.
6. The owner types the code and confirms with Windows Hello within 60 s. A wrong code, a cancel or a
   timeout → `bye` `denied`.
7. Agent stores the hub's static key, name, role and a new `ak` for it, then sends
   `{ "t": "pair.result", "ok": true, "role": "admin", "agent": { … }, "ak": "…" }` and closes the
   pairing session.
8. The app stores `{agent id, agent public key, ak, last host, port}` and connects on `/v2`. Existing
   hubs get `ev.peers` with `change: "added"` — another parent sees at once if a device was added.
   If pairing fails, the app says why ("denied on the PC", "code already used — start again on the
   PC"), never just "try again".

On `/v2/pair` only `pair.hello`, `pair.result` and `bye` are allowed.

**Why this is safe.** Someone who sniffs the Wi-Fi sees at most the v1 code, not `s`. A wrong `s`
fails at the first message. A fake PC can't answer, because the app pins `k`. Someone who photographed
the QR and pairs first is caught twice: the PC only accepts the code the parent types in from *their*
phone — the child's phone shows a different one — and as soon as the parent's phone also scans, the
pairing is cancelled, the parent's phone shows "code already used", and the PC warns that the QR may
have been photographed.

## 9. Discovery (step A6)

The agent announces `_gametimer._tcp` over mDNS with TXT `v=2` and `tags=<tag>,<tag>,…` — one
13-character tag per paired hub:

`tag = base32( BLAKE2b(outlen = 32, key = ak, input = "GameTimer discovery v2" ‖ UTC date "YYYY-MM-DD")[0..8) )`

- The service instance name and the advertised host name are **random** (`gt-<8 random base32>`),
  never the Windows computer name.
- The app tries the last known host first; if that fails it browses mDNS and looks for its own tag
  for today or yesterday (around midnight).
- Tags change daily and mean nothing without the hub's `ak`, so the PC can't be tracked on a foreign
  network by a stable id. An observer can count how many hubs are paired; accepted.
- Discovery only *finds* the PC; trust always comes from the pinned key.

## 10. Relay readiness (Phase B — design sketch, not normative yet)

- The agent dials **out** to the relay over one WebSocket and multiplexes several hubs on it. The
  relay framing will wrap each Noise message unchanged: `{ type, route token / stream id } + opaque
  Noise message`. So "one WebSocket message = one Noise message" holds per *stream*, not per socket.
- **Routing tokens rotate** and are derived from each hub's `ak` with their own label
  (`"GameTimer route v2"`), like the discovery tag — the relay never sees a stable agent id.
- Rate limits and the failed-handshake counter apply **per stream / token**, not per IP (every hub has
  the relay's IP).
- What the relay still sees (stated in the privacy promise): IP addresses, timing, message sizes, and
  which tokens talk to each other within a rotation period. Never content, keys or ids.

## 11. Security assumptions and residual risks

- **Windows account (decision D6).** The agent runs in the parent's own Windows account, which the
  child also uses — the common home set-up and the owner's. Windows Hello there verifies the *parent*.
  Anyone sitting at the unlocked session can, however, run code as that user and so read the
  DPAPI-protected agent key and state. This is an **accepted residual risk**, the same ceiling as
  "Protection from closing". Not handled yet (known gap): a separate standard child account, where
  Windows Hello would verify the child — that needs an in-app parent PIN and/or the agent as a
  Windows service.
- **Lost or stolen parent phone.** It can be revoked from the PC or by an admin. App-side re-auth: §12.
- **Metadata.** See §10. The network sees that a phone and the PC talk, when, and how much.
- **Admin rights.** A child with administrator rights on the PC can defeat any local protection.

## 12. Hub behaviour (non-normative)

- **Re-authentication** with the phone's biometrics / PIN: before any **admin** action (revoke
  another parent, change roles), and when the app was last unlocked more than **14 days** ago. Daily
  controls don't ask every time.
- Show a clear notice on `ev.peers` `change: "added"`.
- Reconnect with back-off; resend unanswered state-changing requests with the **same** `rid`.

## 13. Changes in draft 2 (review findings → fixes)

| Finding | Fix |
|---|---|
| F1 pairing secret = v1 cleartext code | separate 32-byte secret in the URL fragment (§8.2) |
| F2 first scanner wins, no confirmation; any hub revokes any other | 6-digit code + confirmation on the PC; admin / parent roles; last admin protected; `ev.peers` notice (§8) |
| F3 agent key readable in the child's session | stated as assumption D6 + residual risk; parent-account set-up (§11) |
| F4 msg 1 replay, no key confirmation | `ts` counter + the `ready` rule (§4.2–4.3) |
| F5 no idempotency / concurrency | `rid` + 10-min result cache; `rev` / `ifRev` / `conflict`; ordering rule (§5) |
| F6 relay claim too strong | relay framing sketch, per-stream limits, rotating route tokens (§10) |
| F7 DoS and lockout | Origin rejected, timeouts, connection caps, keepalive, own counters (§3) |
| F8 silent version breaks | major by path (404), minor in `ready` / `hello` (§3, §6) |
| F9 encodings ambiguous | exact id derivation, base32 / base64url, I-JSON, size limits, Noise traps, vector sources (§4.4–4.6) |
| F10 unbounded history | `limit` ≤ 100, cursor, `more` (§7.2) |
| F11 sessions never end; revocation | 24 h lifetime; revoke closes sessions (§3, §8.1) |
| F12 privacy leaks | rotating mDNS tag; PC name in fragment; names only after the handshake (§9, §8.2, §4.2) |
| F13 ambiguous semantics | duplicate `id`, outstanding cap, `current`, peer id, `timer.start` units, `now` + hub-side countdown (§5, §7) |
| F14 overstated guarantees | §4.2 states exactly what msg 1 does and doesn't get |
| S1 / S2 / S3 | PSK pattern for pairing (IKpsk2 in draft 2, IKpsk1 since draft 3); `bye` with reason after the handshake; hub checks `hello.agent.id` |

### Draft 3 (second review)

| Finding | Fix |
|---|---|
| N1 `psk2` can't be checked at msg 1; secret burnt by any msg 1 | pairing uses **IKpsk1**; `s` is used up only at `pair.hello`; up to 3 attempts in flight; vector coverage checked in A2 (§3, §4.1, §4.6, §8.2) |
| N2 the code on both screens doesn't stop a photographed QR | the owner **types** the phone's code on the PC; two different keys for one `s` cancel the pairing; clear errors on the phone (§8.2) |
| N3 `ts` can lock out a real phone | `ts` dropped; the `ready` rule is enough; hub keys device-only (§4.2, §4.4) |
| N4 `rid` cache underspecified | 256 / hub, 10 min, persisted, body hash, errors cached, cleared on revoke, hub gives up after 5 min (§5) |
| N5 one shared `ak` strands offline hubs | one `ak` per hub; revocation touches only that hub; `ev.keys` removed (§4.4, §8.1, §9, §10) |
| N6 last admin vs PC override | the PC may remove the last admin; next pairing becomes admin (§8.1) |
| N7 one IP fills all handshake slots | at most 2 pending per IP (§3) |
| N8 mDNS leaks the computer name | random instance and host names (§9) |
| N9 nits | exact `h`; `conflict` → `value`; `hours` / `minutes` exception; tag length; `/v2/pair` message set; psk `MixKey(e)` |
| M1 "two phones" rule only caught simultaneous attempts | `s` stays valid for msg 1 for its whole window; a later phone gets `bye` `code_used` and the pending pairing is cancelled (§8.2) |
| Residual (Low, accepted) | several LAN devices can still fill the 8 handshake slots; a replayed msg 1 can occupy a pairing slot for 5 s |
