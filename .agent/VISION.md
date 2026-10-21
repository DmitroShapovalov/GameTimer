# Product Vision — cross-device, privacy-first parental control

> Status: **north star, not yet built.** This is the direction the product is growing toward.
> Today's shipped product (the Windows app, v1.x) becomes the first *module* in this model.
> The detailed, dated engineering plan lives in `ROADMAP.md`; this document is the "why" and the shape.

## 1. Vision

One app in the parent's hand that manages a child's screen time and apps across **all** of the
child's devices — the Windows PC, the Android phone, the iPad — from a single place. No per-device
tools, no web consoles, no accounts on someone else's cloud. The product's promise, and its
differentiator from Qustodio / Family Link / Bark / Screen Time, is **privacy**: the family's data
never lives on a server or on any device the family does not control.

GameTimer today is a Windows-only game-time limiter. The vision keeps everything it already does and
reframes it: the Windows app is the first **agent**; the next products are a **parent app** (the hub)
and **agents** for the other platforms.

## 2. Principles (non-negotiable)

1. **One control point.** The parent app is the single place to see and set everything, for every
   child and every device.
2. **Pluggable modules.** Each controlled platform is an *agent module* that plugs into the hub
   through one common contract. Adding a platform never changes the hub's core — it adds a module.
3. **Data residency (the privacy promise).** Policy, usage, history and app lists live **only** on
   devices the family controls — the parent's phone and the child's devices. **Nothing readable is
   ever stored or retained on a server, a relay, or any device outside the family's control.** We
   collect no analytics and keep no copy.
4. **Local-first.** On the same network the hub talks to an agent directly, with no middle party at
   all. A relay exists only to connect them when they are apart — and it is a *dumb, zero-knowledge
   pipe* (see §5).

> **Honesty up front (the two hard walls).** Two things are set by platform owners and the laws of
> networking, not by how much we code. (a) **What an agent can do on a phone is capped by Apple/Google**
> — on iOS "see which app is open" is deliberately almost invisible to third parties. (b) **Fully
> serverless remote control is impossible** — two devices on different networks need *some* rendezvous
> point. We meet the spirit of principle 3 with a zero-knowledge relay, not by pretending the relay
> doesn't exist. Both are detailed below.

## 3. Architecture

Three roles, one protocol:

```
          ┌──────────────┐     commands        ┌─────────────────────┐
          │   HUB        │ ──────────────────▶ │   AGENT             │
          │ parent app   │                      │ on a child device   │
          │ (phone, base)│ ◀────────────────── │ (enforces + reports)│
          └──────────────┘     state / events   └─────────────────────┘
                 ▲                                         │
                 │  TRANSPORT: LAN direct (E2E) │ relay (E2E) │ push wake
                 └─────────────────────────────────────────┘
```

- **Hub** — the parent app. Holds the *intended* policy (limits, schedules, allow/block lists) as the
  parent sets it, shows the live state of every child device, and is where everything is configured.
  The base is a phone app; a desktop hub can come later from the same codebase.
- **Agent** — runs on the controlled device. It *enforces* the policy with that platform's real
  mechanisms, and *reports* state and history back. The Windows app is today's only agent.
- **Transport** — how hub and agent talk. Pluggable: direct on the LAN, through a relay when apart,
  woken by push. The payload is identical regardless of path.

### The module contract (the key abstraction)

Every agent speaks the same small protocol and, crucially, **declares its capabilities** — what it
can actually do on that platform. The hub renders only the controls an agent advertises, so modules
with very different powers (a PC that can do everything vs. an iPhone that can do a little) share one
UI without the hub knowing platform specifics.

A capability descriptor is roughly:

```
agent {
  id, deviceName, platform, version
  capabilities: [ time-budget, schedule, block-apps, usage-report,
                  lock-device, shutdown, tamper-resist, ... ]   // only what's truly possible
}
commands:  set-policy, start/stop/extend, block/allow <app>, lock, ...   // hub → agent
events:    state, usage, app-foreground, limit-reached, tampered, ...     // agent → hub
```

This is a natural generalization of what already exists: `TimerController` is the Windows agent's
policy engine; the REST/WS API is an early, Windows-specific version of this contract.

## 4. What a module can really do (honest capability matrix)

| Capability                         | Windows (have) | Android            | iOS                         | Console (Switch/PS/Xbox) | Network module* |
|------------------------------------|----------------|--------------------|-----------------------------|--------------------------|-----------------|
| Time budget / schedule enforcement | Full           | Yes (needs perms)  | Yes (Family Controls)       | Vendor app only          | Partial         |
| Block / allow specific apps        | Full           | Yes (DPC/A11y)†    | Shield apps (opaque tokens) | No                       | No              |
| See which app is running / usage   | Full           | Yes (UsageStats)   | **Very limited** by design  | No                       | DNS/traffic only|
| Lock / shut down the device        | Full           | Lock only          | No (only shield)            | No                       | No              |
| Resist child tampering             | Std account    | Device Owner       | Family Sharing child        | Vendor account           | N/A             |
| Remote (out of home)               | via relay      | relay / push       | relay / push                | vendor cloud             | router-dependent|

\* **Network module** = an agent that runs on the home router or a small always-on box (e.g. a
Raspberry Pi) and controls *network access* (DNS filtering, cut a device's internet on a schedule).
It is the only realistic way to "control" a device we can't put an agent on — including consoles.

† Android app-blocking via Accessibility/Device-Policy is powerful but is a **Play-policy minefield**
and needs intrusive permissions.

**Read this matrix as the honest ceiling.** "Control any device" is the goal; the truth is *full*
control on Windows, *good* control on Android, *limited* control on iOS, and *network-level only* for
consoles. The module model is what lets us ship each at its real capability without over-promising.

## 5. Transport & the "no server" question

Principle 3 forces precision. Three paths, in order of preference:

1. **LAN direct (no middle party).** Same Wi-Fi: hub ↔ agent talk directly. Security by **pinned TLS**
   (the agent's self-signed cert fingerprint is delivered in the pairing QR — no certificate authority
   needed, which is exactly what the browser/PWA could not do) *or* app-layer end-to-end encryption.
2. **Zero-knowledge relay (when apart).** Different networks + NAT make direct impossible, so a tiny
   relay introduces the two sides and forwards bytes. It is a **dumb pipe**: everything is
   **end-to-end encrypted and authenticated** before it reaches the relay, keyed from the pairing
   secret, so the relay **cannot read or forge** a command and **persists nothing**. This honours
   "no data on an uncontrolled device" — the relay holds ciphertext in flight and nothing at rest.
3. **Push wake-ups.** APNs/FCM deliver a tiny "something changed, reconnect" nudge so an agent can act
   while backgrounded. These route through Apple/Google by necessity; the payload is minimal and
   carries no family data.

So the precise, truthful promise is: **"We store none of your data on our servers; it lives only on
your devices, and the connection is end-to-end encrypted."** Not "there are no servers" — that cannot
be true for remote control, and claiming it would be dishonest.

## 6. Privacy & threat model

- **At rest:** policy on hub + agent; history on the child's agent, mirrored to the hub on demand.
  Never on the relay, never with us. No telemetry.
- **In transit:** E2E encrypted + authenticated end to end; the relay and the Wi-Fi both see only
  ciphertext.
- **Keys:** every hub and agent has its own static key pair; the pairing QR carries the agent's
  public key and a one-time secret, and the parent's key is learned during a pairing the PC owner
  confirms. Private keys never leave the device. (v1 PWA tokens stay stored hashed, as today.)
- **Local tamper:** the same stance already shipped on Windows (DPAPI-protected state, rollback,
  tamper event) generalizes per platform (Android Device Owner, iOS Family Sharing child).
- **Transparency / legal:** parental monitoring must be visible, not hidden (store rules; and consent
  norms in the EU/NL). The product is openly a parental-control tool, not stalkerware.

## 7. Roadmap (phases, each gated by a spike for the external walls)

- **Phase A — Generalize the core + parent app over LAN.** Extract hub/agent/transport and the
  capability protocol from the Windows code; build the parent app (Android first, native UI on a Kotlin Multiplatform core — D7) and have
  it control the *existing Windows agent* over pinned-TLS/E2E on the LAN. Lowest risk — both sides are
  ours. Delivers a real cross-platform parent app and sets the contract.
- **Phase B — Zero-knowledge relay + remote + push.** Design the E2E protocol first; the relay is a
  dumb forwarder. Adds out-of-home control and push wake-ups. (This is the old Phase 2b, promoted.)
- **Phase C — Android agent.** After a **feasibility spike** (usage read, app block, survive-tamper,
  Play-policy review). Medium risk.
- **Phase D — iOS agent.** After an **entitlement + Family Controls spike**. Ships limits/shields;
  sets the expectation that per-app *visibility* is minimal. Gated by Apple approval.
- **Phase E — Network module (router / Pi) + console research.** The honest path to consoles and
  un-agentable devices: schedule-based network cut-off, DNS filtering. Vendor console integrations
  (Microsoft Family, Nintendo/Sony parental APIs) researched case by case — expected to be limited.

Spikes come *before* commitment because Phases C/D/E depend on walls we don't own.

## 8. What carries over from v1 (nothing wasted)

The Windows app already contains the right shapes: `TimerController` (policy engine → agent core),
`ProtectedStore` (per-platform tamper-resistant state), the daily schedule, the session history, the
pairing/token/QR flow, and the LAN-only transport. Phase A is largely *reorganizing* these behind the
hub/agent/transport seam, not rewriting them.

## 9. Open decisions (to settle before Phase A)

- ~~**Mobile stack**~~ — decided: **native apps on a Kotlin Multiplatform core** (D7, replaces D1).
- ~~**E2E protocol**~~ — decided: **Noise_IK over libsodium primitives** (see §11, D2).
- **Relay hosting & cost:** small VPS vs. serverless WebSocket; domain, monitoring, a privacy page.
- **Name / brand:** "GameTimer" is now too narrow (it's device & app control, not just game time).
  Possible rename — weigh against the churn (`AssemblyName=SystemMonitor`, repo, installer, stores).
- **Monetization & legal:** free/one-off/subscription; EU/NL consent and transparency; who is liable
  for enforcement gaps.
- **Keep the PWA?** Yes, as the zero-install option for the Windows agent; the native parent app is
  the richer path.

## 10. Reality check (keep honest)

- Windows and the relay are ours → low platform risk.
- Phones are Apple's/Google's turf → the capability ceiling and store review are the real blockers,
  not engineering effort.
- Consoles are mostly closed → "modules for them" realistically means network-level control, not an
  on-device agent.
- "No server" in the literal sense is impossible for remote; "no data on our servers" is both possible
  and the thing worth promising.

## 11. Decisions log

### D1 — Mobile stack: Flutter (2026-10-18) — *superseded by D7*

> Kept for the record. Replaced on 2026-10-21: its main premise (the owner reviews Dart line by line)
> turned out not to hold — see D7.

**Decision.** The parent app and the phone agents (Android, iOS) are written in **Flutter / Dart** — one
stack for every new client. The existing **Windows agent stays in C#** for now, behind the protocol;
rewriting it in Flutter Desktop (Win32 via FFI) is an option for later, not a goal.

**Why.**
- The owner reads and reviews Dart, not C#, Swift or Kotlin. Being able to follow and control the code
  outweighs reusing the existing C# (which would only be reused by the agent, not reviewed by the owner).
- One stack across iOS, Android, desktop and web; the most mature cross-platform option for mobile.
- .NET MAUI does target iOS/Android, but its mobile ecosystem is smaller and less mature; without the C#
  reuse argument it has no advantage here.
- Capacitor / React Native were considered: their plugins can reach native APIs, but the hard parts
  stay native anyway and a WebView/JS app is weaker in the background, which is what an agent needs.
  Capacitor remains a possible shortcut to wrap the existing PWA, but it would mean two stacks.

**Consequences.**
- **Native adapters are unavoidable** in the phone agents, whatever the stack: iOS Family Controls needs
  a Swift app extension (out of process, data shared via App Groups); Android usage / blocking /
  tamper-resistance is Kotlin behind platform channels. Rules for them:
  - keep them **thin** — only the OS call; all logic (schedules, budgets, decisions) lives in Dart;
  - a **narrow, documented contract** to Dart ("block app X", "usage for day D", "lock now");
  - tests, and a plain-language description of what each adapter does in every PR touching it.
- The protocol and crypto must interoperate between **Dart (hub, phone agents)** and **C# (Windows
  agent)** — a constraint on decision D2 (both libsodium and Noise have Dart and .NET implementations).

### D2 — End-to-end protocol: Noise_IK over libsodium primitives (2026-10-18, amended)

**Decision.** Every hub ↔ agent session is protected by the **Noise Protocol Framework**, handshake
pattern **IK** (`Noise_IK_25519_ChaChaPoly_BLAKE2b`): the hub (initiator) knows the agent's static
public key from the pairing QR, and sends its own static key *encrypted* in the first message.

> *Amended during A1:* first chosen as **KK**, changed to **IK**. KK needs the agent to know *before* the
> first message which parent is calling, so with several parent phones the parent would have to name
> itself in clear text. IK carries the parent's key encrypted, the agent checks it against its
> paired-parents list. Session guarantees: mutual authentication and forward secrecy for everything
> after the handshake. The first handshake message itself can be replayed, so it carries nothing
> sensitive and the agent acts only after the parent's first encrypted message (the `ready` rule). It is
> the pattern WireGuard uses. Pairing uses the PSK variant `Noise_IKpsk1` with the one-time secret from
> the QR. Full contract: `docs/protocol.md` (reviewed independently twice before approval). The primitives (X25519, ChaCha20-Poly1305, BLAKE2b) come from **libsodium**
(Kotlin Multiplatform libsodium bindings on the phones, .NET `NSec` / `Sodium.Core` on Windows; see D7). The same encrypted frames travel over the LAN and,
later, through the relay — the transport only carries opaque bytes.

**Why.**
- Pinned TLS is simple on a direct connection but awkward to carry end-to-end *through* a relay
  (tunnelling a raw TLS stream inside a WebSocket is clumsy on mobile stacks) — it would mean one design for
  home and another for remote.
- Bare libsodium boxes would force us to invent our own handshake, which is where real-world crypto
  goes wrong (replay, forward secrecy, binding keys to the session).
- Noise is a small, well-analysed spec used by WireGuard and WhatsApp: mutual authentication and
  forward secrecy are designed in, its limits are documented per message, and it is transport-agnostic.

**Pairing (replaces the v1 QR flow for the new protocol).**
- One QR serves both: the v1 PWA code in the URL, and in the URL *fragment* (never sent to a server)
  the agent's **static public key** and a separate 32-byte one-time secret.
- The app runs `Noise_IKpsk1` with that secret; the phone shows a 6-digit code derived from the
  handshake, the PC owner **types it in** and confirms with Windows Hello (D4). Only then does the
  agent store the parent's key. If a second phone uses the same QR, the pairing is cancelled.
- From then on every connection is an IK handshake — neither side can be impersonated, even by
  whoever controls the Wi-Fi or the relay.
- Revoking a parent device = deleting its public key on the agent (and vice versa).

**Rules.**
- Implement the Noise state machine **strictly to the spec** on top of libsodium — no custom
  constructions. Both implementations (Kotlin and C#) are checked against **published Noise test
  vectors** (cacophony; snow as cross-check), and a Kotlin ↔ C# live handshake runs in CI, so neither
  side can drift.
- Private keys never leave the device; stored with the platform's protected storage (DPAPI on Windows,
  Keychain / Keystore on phones).

**Honest limit (metadata).** The relay — and anyone on the network — can still see *which* devices
talk, *when*, and *how much* data flows, but not the content. Hiding that too would need Tor-class
mixing, which is out of scope. The privacy promise states this plainly.

### D3 — Parent roles (2026-10-18)

One **admin** by default: the first phone paired to a PC. An admin can make another parent admin; the
last admin can't be removed from a phone. Other parents (**parent** role) have all daily controls but
can't remove other parents or change roles. The PC owner (Windows Hello) can always change roles and
remove anyone, including a lost admin phone. Details: `docs/protocol.md` §8.1.

### D4 — Pairing confirmed on the PC (2026-10-18)

A new phone is paired only after the PC owner **types in** the 6-digit code shown on that phone and
confirms with Windows Hello. Typing, not glancing, so a child who photographed the QR can't slip their
phone in. Other parents are notified of every new device.

### D5 — Rotating identifiers, per parent (2026-10-18)

Nothing on the network carries a stable PC identifier: mDNS discovery tags and (Phase B) relay routing
tokens rotate daily and are derived from a secret each parent phone shares with the PC (one per
phone, so revoking one phone never disturbs the others). Service and host names in mDNS are random.

### D6 — Windows agent runs in the parent's account (2026-10-18)

Design for the common home set-up, and the owner's: the child uses the parent's Windows account, so
Windows Hello verifies the parent. Accepted residual risk: anyone at the unlocked session can run code
as that user and read DPAPI-protected keys and state — the same ceiling as "Protection from closing".
Known gap for later: a separate standard child account (there Windows Hello would verify the child),
which needs an in-app parent PIN and/or the agent as a Windows service.

**Lost phone (related, low priority).** The parent app asks for the phone's biometrics / PIN only
before admin actions and if it wasn't unlocked for 14 days; daily controls don't ask every time.

### D7 — Mobile stack: native apps on a Kotlin Multiplatform core (2026-10-21, replaces D1)

**Decision.**
- Phone apps are **native**: Android in **Kotlin + Jetpack Compose**, iOS in **Swift + SwiftUI**.
- Their shared, non-UI core is **Kotlin Multiplatform** (`shared/`): protocol v2 messages, Noise IK /
  IKpsk1 on libsodium, models, schedule and policy logic, the transport client. One implementation,
  compiled to an Android library and an iOS framework.
- Agent OS integration is native with no bridge layer: Swift app extensions for Family Controls on
  iOS, Kotlin services on Android.
- The Windows agent stays in C#.

**Why.**
- D1 rested on the owner reading Dart. In practice the owner validates **behaviour**, not code line by
  line, in any language — so "one language I can read" is no longer the deciding factor; product
  quality and maintenance are.
- The phone agents are native anyway. A native app removes the framework ↔ OS bridge (platform
  channels), so there are fewer moving parts and the best background behaviour, which an agent needs.
- KMP instead of two fully separate native apps: the logic and the crypto are written **once** for both
  phones, and the protocol exists in **two** languages (Kotlin, C#) instead of three. KMP is stable
  (since late 2023) and used in large production apps.

**Honest notes.**
- Store review depends on *what* the app does (Apple's Family Controls entitlement, Google's
  Accessibility / device-policy rules), not on the framework — native is not a shortcut there.
- The UI is written twice (Compose, SwiftUI). Accepted; Android comes first.
- Kotlin/Native on iOS has some friction (build times, Swift interop through generated headers).
  Mitigation: keep the shared API small and plain.
- iOS builds need macOS (a CI runner) and an Apple developer account.

**How the code is validated.** The owner tests on real devices and accepts each step. CI enforces the
Noise test vectors and a live Kotlin ↔ C# interop test. Security-relevant changes get an independent
reviewer agent, as done for the protocol. Every PR has a plain-language description.

**Consequences.**
- Phase A builds the **Android** parent app first; the iOS app follows on the same `shared` core.
- D2's Kotlin side uses KMP libsodium bindings (e.g. `multiplatform-crypto-libsodium-bindings`).
  A2 starts by confirming they cover X25519, ChaCha20-Poly1305 (IETF) and BLAKE2b on Android and iOS.

