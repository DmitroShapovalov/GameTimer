# Noise test vectors

`noise-vectors.json` holds the published test vectors for the Noise protocols GameTimer uses
(`docs/protocol.md` §4), extracted unchanged from:

- **cacophony** — <https://github.com/haskell-cryptography/cacophony/blob/master/vectors/cacophony.txt>
  (public domain, The Unlicense): `Noise_IK_…`, `Noise_IKpsk1_…`, `Noise_IKpsk2_…` (`25519_ChaChaPoly_BLAKE2b`).
- **snow** — <https://github.com/mcginty/snow/blob/main/tests/vectors/snow.txt> (Apache-2.0 OR MIT):
  `Noise_IK_…` as an independent cross-check. snow has no `IKpsk1` vector.

Both the C# (`tests/GameTimer.Protocol.Tests`) and the Kotlin tests read this file; a change to either
implementation that breaks a vector fails CI. Each vector has fixed static and ephemeral keys, the
prologue and PSKs, the expected ciphertext of every message (two handshake messages, then transport
messages alternating initiator / responder), and the final handshake hash.

## Protocol vectors

`protocol-vectors.json` holds ids (§4.4), pairing codes (§8.2), base32 and base64url (§4.5), computed
by `gen_protocol_vectors.py` with Python's `hashlib` / `base64` — an implementation independent of
both the C# and the Kotlin code, which are both tested against it.
