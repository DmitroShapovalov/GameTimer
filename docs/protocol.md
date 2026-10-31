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
  `revoked`, `expired`, `denied`, `code_used`, `busy`, `shutdown`, `internal`, and (Phase B)
`remote_off` (the PC owner turned "Control from anywhere" off, §10.2). A peer that doesn't
  read the `bye` within 2 s is cut off; nothing it sends after the `bye` is executed.

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
- **At most 16 outstanding requests** per session; more → `error` `busy`. (An agent may also handle a
  session's requests strictly one after another; then the limit never triggers and later requests
  just wait.)
- **Idempotency.** For each hub the agent keeps a cache: `rid` → (hash of the request body, reply).
  - Entries live **10 minutes**, at most **256 per hub** (oldest dropped first), and are **persisted**
    in `ProtectedStore`, so a PC restart doesn't make a resent `timer.extend` run twice.
  - A known `rid` with the **same** body is not executed again; the stored reply is returned. A known
    `rid` with a **different** body → `error` `bad_request`. "Same body" = the request without `id`,
    byte for byte: a hub resends the message it sent before, with only `id` changed.
  - `error` replies are cached too, except `busy` and `internal`.
  - A hub's entries are deleted when it is revoked.
  - The hub stops resending after **5 minutes** (well inside the 10-minute window) and asks the user.
- **Concurrency.** `settings` and `schedule` carry a `rev` (integer, +1 on every change). A write may
  include `ifRev`; if it doesn't match → `error` `conflict` with the current value in `value`.
- **Ordering.** For a successful state-changing request the requesting session gets the `result`
  and **not** the matching `ev.*`; all other sessions get the `ev.*`, in the order the agent's state
  changed. (Across different sessions there is no "before": each hub orders only its own stream.)

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

**Minor versions.** `0`: the initial set. `1` (agent v1.7.1): `timer.end`, `timer.setEnd`,
`timer.stop` with `skipScheduleToday`, and the schedule status `skippedToday`. A hub shows those
controls only when `hello.minor` ≥ 1.

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

An agent with relay support (Phase B) also puts `remoteAccess` (bool, top level) in `hello`: whether
"Control from anywhere" (§10.2) is on. A missing field means `false`.

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

`schedule.status`: `off`, `allowed`, `outsideWindow`, `dayDisabled`, `limitReached`, and (minor 1)
`skippedToday` — the schedule is on but a parent set it aside for today (`timer.stop` with
`skipScheduleToday`); it applies again at local midnight, or once a timer started during the lift
runs out (that timer is the parent's new limit and isn't capped by the schedule). History types
`scheduleSkipped` / `scheduleRestored` record both. History events caused by a hub carry its id in
`hub` (agent v1.7.2), so a hub can tell its own actions from another hub with the same name.

The hub counts down from `remainingSeconds` using **its own monotonic clock** from the moment the
message arrived. `targetTime` and `now` are for display only: the PC clock may be wrong or changed.

### 7.2 Requests (hub → agent)

| Type | Cap. | `rid` | Role | Body | Result |
|---|---|---|---|---|---|
| `state.get` | — | — | any | — | `state` |
| `timer.start` | `timer` | ✓ | any | `mode`; for `interval`: `hours` + `minutes` = duration; for `absolute`: `hours` + `minutes` = local time of day on the agent | `state` |
| `timer.stop` | `timer` | ✓ | any | optional `skipScheduleToday` (minor 1): also set the daily schedule aside until midnight, or until a timer started during the lift runs out | `state`; `not_running` if there was nothing to stop or lift |
| `timer.extend` | `timer` | ✓ | any | `minutes` (±) | `state` |
| `timer.end` | `timer` | ✓ | any | — (minor 1): the running timer ends now, as if its time ran out (the expiry action runs) | `state` |
| `timer.setEnd` | `timer` | ✓ | any | `hours` + `minutes` (minor 1): the running timer now ends at that local time of day on the agent (the next one), capped by today's schedule | `state`; `invalid_time` if that is more than 12 h ahead (a time just passed means "earlier", not "tomorrow"); `schedule_limit` if the schedule allows no later end |
| `interruption.ack` | `timer` | ✓ | any | — | `state` |
| `settings.get` | `settings` | — | any | — | `settings` (incl. `rev`) |
| `settings.put` | `settings` | ✓ | any | any subset of the v1 settings fields, optional `ifRev` | `settings` |
| `schedule.get` | `schedule` | — | any | — | `schedule` (`enabled`, `week[7]`, `rev`) |
| `schedule.put` | `schedule` | ✓ | any | full schedule, optional `ifRev` | `schedule` |
| `history.get` | `history` | — | any | `limit` (1–100, default 50), optional `before` (opaque cursor from `next`) | `events[]` newest first (recording order), `more`, `next` (cursor); a cursor whose event was trimmed meanwhile → `bad_request`, start again from the top |
| `peers.list` | `peers` | — | any | — | `peers[]` |
| `peers.revoke` | `peers` | ✓ | admin (or self) | `peer` (hub id) | `peers[]` |
| `peers.setRole` | `peers` | ✓ | admin | `peer` (hub id), `role` (`admin` / `parent`) | `peers[]` |

`peers[]` entries: `id`, `name`, `role`, `pairedAt`, `lastSeen`, `current` (= this is the hub making
the request).

`settings` from an agent with relay support (Phase B) also carries `remoteAccess` (bool): whether
"Control from anywhere" (§10.2) is on. It is **read-only**: only the PC owner changes it, on the PC.
`remoteAccess` in a `settings.put` is ignored (a hub may send its whole settings object back). A
change on the PC bumps `rev` and sends `ev.settings`. Hubs treat a missing field as `false`.

Error `code`s: the v1 set (`invalid_time`, `already_running`, `not_running`, `invalid_minutes`,
`invalid_threshold`, `invalid_alerts`, `invalid_color`, `invalid_language`, `invalid_schedule`) plus
`bad_request`, `unknown_command`, `unsupported`, `forbidden`, `conflict`, `busy`, `last_admin`, and
`internal` (the agent failed unexpectedly; the request may be retried with the same `rid`), and
(minor 1) `schedule_limit`.

### 7.3 Events (agent → hub)

| Type | When | Body |
|---|---|---|
| `ev.state` | timer started / stopped / expired / extended; schedule allowed ↔ blocked; interruption changed | `state` |
| `ev.tick` | every **30 s** while a timer runs | `state` |
| `ev.alert` | a sound-alert point was reached | `minutes` |
| `ev.settings` | settings changed (on the PC or by another parent) | `settings` |
| `ev.schedule` | schedule changed | `schedule` |
| `ev.history` | a history event was recorded | `event` |
| `ev.peers` | a parent was added, revoked or changed role | `peers[]`, `change` (`added` / `revoked` / `role`), `peer` (hub id) |

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

The agent announces `_sysmon._tcp` over mDNS with TXT `v=2` and `tags=<tag>,<tag>,…` — one
13-character tag per paired hub:

`tag = base32( BLAKE2b(outlen = 32, key = ak, input = "GameTimer discovery v2" ‖ UTC date "YYYY-MM-DD")[0..8) )`

- The service instance name and the advertised host name are **random** (`pc-<8 random base32>`),
  never the Windows computer name, and new every UTC day (with the tags) and every agent start.
- **TXT layout.** `v=2`, then the tags in groups of at most 17 per TXT string (a string holds 255
  bytes): `tags=…`, `tags2=…`, `tags3=…` (a DNS-SD key may appear only once). With no paired hub the
  agent announces nothing at all.
- **Known networks only.** The agent announces only on a network where a hub already had a session
  (Windows: identified by a hash of the subnet and the default gateway's MAC address; the last 16 are
  kept with the agent's state). On a café's or school's Wi-Fi it stays silent. After a move to a new
  network the hub reconnects by the stored address once, or the parent pairs again. A network
  whose gateway MAC can't be read counts as unknown. A new router (new gateway MAC) is a new network
  too: until a hub has had a session there, a changed PC address needs the stored one or re-pairing.
  An agent upgraded from a build without this list takes the network it first runs on as known.
- The service type is deliberately neutral (`_sysmon._tcp`, like the Windows exe and firewall rule
  names), so a Bonjour browser on the LAN doesn't name the product.
- The app tries the last known host first; only if no session could be opened there it browses mDNS
  for its own tag for yesterday, today or tomorrow (around midnight, and a PC clock that is somewhat
  off) — at first every 20 s, doubling up to every 5 minutes while the PC stays away.
- Found addresses are only **candidates**: the app tries every match (tags are public, anyone can copy
  one) and adopts and stores an address only after the IK handshake with the pinned key succeeded
  there. Only LAN addresses are tried (as for the pairing QR), never the phone's own loopback. A forged announcement can't
  impersonate the PC; it costs a failed attempt. A host that relays TCP to the real PC does get
  stored, but sees only Noise ciphertext — no more than anyone already in the LAN path; accepted.
- Records (RFC 6762 / 6763): PTR `_sysmon._tcp.local` → instance; SRV (port, host); TXT; A (the
  PC's LAN IPv4). Announced twice at start and on every change (new tags at the UTC date change, a
  paired or revoked hub, a new address), withdrawn with TTL 0 when the server stops. Queries are
  answered only from the subnet the agent announces on; QU and legacy-unicast queries get unicast
  replies, rate-limited per source (1/s) and in total (20/s) since a UDP source can be forged.
- Tags change daily and mean nothing without the hub's `ak`, so the PC can't be tracked on a foreign
  network by a stable id. An observer can count how many hubs are paired; accepted.
- Discovery only *finds* the PC; trust always comes from the pinned key.

## 10. Relay (Phase B)

A phone that can't reach the PC on the LAN reaches it through a **relay**: the PC keeps one outbound
WebSocket to the relay, the phone opens a WebSocket to the relay, and the relay joins the two and
copies bytes it can't read. Everything inside — Noise (§4), `ready`, `hello`, requests, events (§5–§7)
— is exactly what travels on the LAN. The relay is **not trusted**: trust still comes only from the
keys pinned at pairing.

```
  hub ── wss://<relay>/v2/hub/<route> ──► relay ◄── wss://<relay>/v2/agent ── agent
         one socket = one Noise stream             one socket, many streams (§10.2)
```

The relay address is built into the app (default `wss://gametimer-relay.fly.dev`); it is a setting,
so it can move later. Pairing never goes through the relay: `/v2/pair` stays LAN-only (§8.2), since
the QR is shown on the PC anyway.

### 10.1 Route claims and routes

Each paired hub has, per UTC day, a secret **claim** and a public **route** derived from it:

```
claim = BLAKE2b(outlen = 32, key = ak, input = "GameTimer route v2" ‖ UTC date "YYYY-MM-DD")[0..16)
route = BLAKE2b(outlen = 32, key = none, input = "GameTimer route id v2" ‖ claim)[0..16)
```

- The **agent registers claims** (16 bytes each); the relay computes each route itself and indexes
  by it. The **hub dials the route** as 26 base32 characters (§4.5, uppercase; the 2 unused bits of
the last character are zero). So a route that leaks
  from a log can be dialled but never registered: only someone who knows `ak` (the hub, the agent) —
  or the relay itself — can make the relay deliver a route's streams.
- The output length is part of BLAKE2b: outlen 32 truncated to 16 bytes is **not** outlen 16.
- The agent registers **yesterday's, today's and tomorrow's** claim of every paired hub, at most
  63 claims (21 hubs, whole hubs only), preferring the hubs used most recently (`lastSeen`, else
  `pairedAt`). Each `register` lists them in random order. A hub left out can't come back through
  the relay (its use there is never recorded); it comes back after its next session on the LAN.
- The hub dials **today's** route by its own clock; after a 404 it tries tomorrow's and yesterday's
  once each (a PC clock that is about a day off).
- Test vectors: `routeTokens` in `tests/vectors/protocol-vectors.json` (independent Python generator:
  `claim`, `route`, `token`).

### 10.2 Agent socket: `wss://<relay>/v2/agent`

One WebSocket per agent. Every message is **binary** and is one frame: a 1-byte type, then fields in
big-endian order. A frame's length must match its type exactly.

| Type | Name | Direction | Body (exact length) |
|---|---|---|---|
| `0x01` | `register` | agent → relay | 0..64 claims, 16 bytes each (a multiple of 16). Replaces the agent's whole set; duplicates count once. |
| `0x02` | `open` | relay → agent | `stream` (u32, non-zero) + the 16-byte **route** the hub dialled (20 bytes) |
| `0x03` | `data` | both | `stream` (u32) + one Noise message, unchanged (1..65 535 bytes; an empty one is malformed) |
| `0x04` | `close` | both | `stream` (u32) + WebSocket close code (u16) (6 bytes) |
| `0x05` | `ping` | both | 0..8 opaque bytes |
| `0x06` | `pong` | both | the bytes of the `ping` it answers |
| `0x07` | — | — | reserved for the push wake-up (step B5) |

**Malformed or unexpected frames.** A text message, a frame of the wrong length (or longer than
5 + 65 535 bytes), more than 64 claims (counted before duplicates are removed) or an unknown type from the agent → the relay closes the socket with 1002 (1003 for text).
A malformed frame from the relay → the agent closes the socket and reconnects. The agent ignores
frame types it doesn't know (room for later additions); the relay doesn't.

**Register.** The agent must send `register` within 5 s of connecting (else the relay closes with
1008), and again whenever the membership of its set changes (a hub paired or revoked, the UTC date
changed, a hub moving in or out of the 21). At most one `register` per second; more → the relay closes
the socket with 1008. There is no acknowledgement: the agent accepts an `open` whose route belongs to
its current set **or to any set it replaced in the last 30 s**, and closes any other stream at once
with 1008. A claim another agent socket already holds moves to the newest registration (the old socket
is half-dead, or the same agent state runs twice); streams already open stay where they are.

**Streams.** The relay picks the stream id: non-zero, unique on that socket, never reused while the
socket lives. A `data` or `close` for a stream that is unknown or already closed is ignored (it
crossed a `close` in flight).

**Close codes.** A `close` from the agent becomes the close of the hub's WebSocket with the same code
when that is a code an endpoint may send (1000, 1001, 1008, 1011, 3000–4999), otherwise 1000. The
hub's close reaches the agent the same way; a hub socket that just drops is reported as 1001. When the
agent socket goes away, the relay closes every hub socket of it with 1001.

**Keepalive.** Either side sends `ping` after 30 s without sending anything and closes the socket
after 90 s without receiving anything. When the relay restarts (a deploy) it closes agent sockets with
1012; the agent reconnects as after any drop. The agent reconnects after a drop with back-off (1 s, doubling
to 5 min, with jitter) and registers again.

**When the agent connects.** Only while "Control from anywhere" is on (off by default; turned on by
the PC owner behind Windows Hello) and at least one hub is paired. It is reported to hubs as
`remoteAccess` in `hello` (§6) and in `settings` (§7.2). Turning it off closes the agent socket and
ends every relayed session with `bye` `{ "code": "remote_off" }`; LAN sessions are untouched.

### 10.3 Hub socket: `wss://<relay>/v2/hub/<route>`

No framing: one WebSocket = one stream, and **one binary message = one Noise message**, exactly as on
`/v2` in the LAN (§3). The relay checks in this order and answers the upgrade with the first match:

| Answer | When |
|---|---|
| 403 | the request carries an `Origin` header (browsers are refused, as in §3) |
| 429 | per-IP limits (§10.4) |
| 404 | `<route>` is not 26 characters of the uppercase base32 alphabet with zero trailing bits, or no agent socket holds it — **the same answer as "PC offline"**, nothing more |
| 503 | that agent socket already carries its maximum of streams |
| 101 | joined: the relay sends that agent `open` |

A text message from the hub → the relay closes with 1003. The hub's encrypted `ping` every 30 s (§3)
keeps the stream alive.

**Hub behaviour.**
- The hub tries the stored LAN address first. If no session is open within **~2 s**, or the phone
  isn't on Wi-Fi / Ethernet, it also dials the relay.
- **Only one path gets `ready`.** The first path on which msg 2 arrives and checks out against the
  pinned key wins: the hub sends `ready` there and closes the other path without sending `ready` on
  it (an unconfirmed handshake of a known key: not a failure for the agent, §3). The hub never sends
  requests on a path it didn't confirm.
- After 404 on today's, tomorrow's and yesterday's route, the hub waits before dialling the relay
  again (20 s, doubling to 5 min, like the LAN search in §9). It doesn't dial the relay at all while
  the last `hello` or `settings` it saw from that PC had `remoteAccess: false` (or none: an agent
  without relay support).
- On the next reconnect it tries the LAN first again.

### 10.4 Limits

**Relay** (no state on disk, no database; everything is in memory). "Per IP" means per IPv4 address
or per IPv6 /64; the IPv4 /24 or IPv6 /48 around it also has 4× each per-IP limit in total, so one
host with many addresses can't take the relay alone. Per-IP limits are generous for hubs because mobile carriers put many phones behind one
address. When the client address can't be read (behind the hosting proxy), the upgrade gets 400.

| Limit | Value |
|---|---|
| New sockets per IP | 10 / s |
| Open sockets per IP | 32 hub sockets, 8 agent sockets |
| Route dials answered 404 per IP | 60 / min, then 429 |
| Streams per agent socket | 16 (the agent's own session limit, §3) |
| Claims per agent socket | 64 |
| Frame size | 5 + 65 535 bytes |
| Idle stream (no `data` either way) | closed after 120 s with 1001 |
| Buffered bytes per stream and direction | 64 KiB (one message always fits); beyond that the stream is closed with 1013 |
| Frames queued to an agent that aren't hub data (`open`, `close`, `ping`, `pong`) | 16 KiB, at most one `pong`; beyond that the socket is closed with 1008 |
| In total | caps on agent sockets and hub sockets (apart), claims, buffered bytes (incl. messages still arriving) and the per-IP table, sized to the machine; beyond them new upgrades get 503, a `register` over the claim cap closes the socket with 1013, and a stream whose data would go over the byte cap is closed with 1013 (that stream only; a full per-IP table first forgets entries without open sockets) |

**Agent.** A relay stream gets the same handshake, `ready` rule, session and `rid` handling as a LAN
socket on `/v2` (it is one more `IFrameTransport`), with these differences, so that a relay — or
someone with a leaked route — can't crowd out the LAN:
- Relay streams waiting for a handshake have **their own pool**: at most 4 in total and 2 per
  *hub* (all routes of a hub count together); they never take a LAN slot (§3), and the agent counts
  them itself, whatever the relay sends. A new relay stream that finds the pool full **evicts**
  instead of being refused: its own hub's oldest waiting stream if there is one, else the oldest of
  the hub with the most waiting, and msg 1 must arrive within **2 s** of
  `open` (a real hub sends it at once). So silent streams on a leaked route can't keep the other hubs
  out.
- The failed-handshake counter of §3 counts **per hub** for relay streams (by the hub whose route was
  dialled; every relayed hub has the relay's IP). Like on `/v2` it only counts: pairing, which it
  gates, never goes through the relay.
- A stream whose msg 1 named the hub's key leaves the pool (it can't be evicted after that); since a
  msg 1 can be replayed, at most 2 such streams per hub wait for `ready` at a time. The agent takes at
  most 20 streams on one relay socket (4 waiting + 16 sessions); more → `close` 1008.
- While "Control from anywhere" is off the agent takes no relayed stream, whatever the relay sends.
- **The route must match the key.** After msg 1, the hub's static key must be the hub whose route the
  stream was opened with; otherwise the agent closes the stream and counts a failure for that route.

### 10.5 What the relay sees

A fully compromised relay can delay, drop or count messages; it can't read the timer, change a
setting, pair a phone or impersonate the PC — every byte between hub and agent is Noise with keys
pinned at pairing.

- It sees IP addresses, connection times, message sizes and timing, and which routes belong to one
  agent socket. Rotation hides a hub from **outsiders** who see its relay URL, not from the relay:
  an agent socket lives across midnight and registers overlapping days, so a curious relay can link a
  PC and each of its hubs from one day to the next ("some GameTimer PC at IP X has N phones, used at
  these times"). It never learns content, keys, names or the ids of §4.4.
- A leaked route lets someone dial that PC: the handshake still needs the hub's private key, failures
  count against that hub only, and waiting streams are evicted (§10.4), so the most it can do is
  slow the relay path down (mostly that hub's: a real stream waits in the pool only until its msg 1,
  about one round trip). It can't be used to take the route over (§10.1). The relay
  itself can always cut off or hijack the relay path — denial of service only; the LAN keeps working.
- One small relay can be overloaded by someone with many addresses (fake agents and hubs need no
  secret): the relay path then fails for everyone until it passes. Denial of service only; the LAN
  keeps working. Accepted for now; watched through the relay's counts and more machines if needed.
- The relay doesn't log request paths (they contain routes) and keeps IP addresses in its logs for a
  few days at most. This is stated in the privacy note.

## 11. Security assumptions and residual risks

- **Windows account (decision D6).** The agent runs in the parent's own Windows account, which the
  child also uses — the common home set-up and the owner's. Windows Hello there verifies the *parent*.
  Anyone sitting at the unlocked session can, however, run code as that user and so read the
  DPAPI-protected agent key and state. This is an **accepted residual risk**, the same ceiling as
  "Protection from closing". Not handled yet (known gap): a separate standard child account, where
  Windows Hello would verify the child — that needs an in-app parent PIN and/or the agent as a
  Windows service.
- **Lost or stolen parent phone.** It can be revoked from the PC or by an admin. App-side re-auth: §12.
- **Metadata.** See §10.5. The network sees that a phone and the PC talk, when, and how much.
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
