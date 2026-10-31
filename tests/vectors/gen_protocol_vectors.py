"""Writes protocol-vectors.json: protocol v2 ids, pairing codes, discovery tags, relay route tokens and encodings
(docs/protocol.md §4.4, §4.5, §8.2, §9, §10).

An independent third implementation (Python hashlib / base64) that both the C# and the Kotlin tests check against.
Run: python3 gen_protocol_vectors.py
"""
import base64
import hashlib
import json


def b32(b):
    return base64.b32encode(b).decode().rstrip("=")


def b64u(b):
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def ident(tag, pub):
    return b32(hashlib.blake2b(tag + pub, digest_size=32).digest()[:10])


def tag(ak, date):
    d = hashlib.blake2b(b"GameTimer discovery v2" + date.encode(), digest_size=32, key=ak).digest()
    return b32(d[:8])


def route(ak, date):
    d = hashlib.blake2b(b"GameTimer route v2" + date.encode(), digest_size=32, key=ak).digest()
    return d[:16]


def code(h):
    n = int.from_bytes(hashlib.blake2b(b"GameTimer pair code v2" + h, digest_size=32).digest()[:4], "big")
    return "%06d" % (n % 1000000)


keys = [bytes(range(32)), bytes([0xFF] * 32),
        bytes.fromhex("31e0303fd6418d2f8c0e78b91f22e8caed0fbe48656dcf4767e4834f701b8f62")]
hashes = [bytes(64), bytes(range(64)),
          bytes.fromhex("1c8fa891cb414fedba6daa7c6f4ae0a6d98e5f9768cc9cecd27e805614943ee9"
                        "c8a1b27fbfb76dc197255c8aa69f6b4285c423840b8bedf45e652ca64f797d81")]
data = [b"", b"f", b"fo", b"foo", b"foob", b"fooba", b"foobar", bytes([0xFB, 0xFF, 0xFE]), bytes(range(16))]

out = {
    "_about": "Protocol v2 encodings and derivations (docs/protocol.md §4.4, §4.5, §8.2, §9, §10), computed "
              "independently with Python hashlib/base64 by gen_protocol_vectors.py.",
    "ids": [{"publicKey": k.hex(), "agentId": ident(b"GameTimer agent id v2", k),
             "hubId": ident(b"GameTimer hub id v2", k)} for k in keys],
    "pairingCodes": [{"handshakeHash": h.hex(), "code": code(h)} for h in hashes],
    "discoveryTags": [{"ak": ak.hex(), "date": date, "tag": tag(ak, date)}
                      for ak in [bytes(32), bytes(range(32))] for date in ["2026-10-24", "2027-01-01"]],
    "routeTokens": [{"ak": ak.hex(), "date": date, "bytes": route(ak, date).hex(), "token": b32(route(ak, date))}
                    for ak in [bytes(32), bytes(range(32))] for date in ["2026-10-24", "2027-01-01"]],
    "base32": [{"hex": d.hex(), "text": b32(d)} for d in data],
    "base64url": [{"hex": d.hex(), "text": b64u(d)} for d in data],
}
with open("protocol-vectors.json", "w") as f:
    json.dump(out, f, indent=1)
    f.write("\n")
