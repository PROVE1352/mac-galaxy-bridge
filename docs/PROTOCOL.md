# Wire protocol (v1 — final as of M2)

The transfer core spoken between the Mac menu-bar app and the Android companion app.
Both codecs are unit-tested against the same byte/digest vectors
(`android/app/src/test/.../FramingTest.kt` ↔ `macos/Tests/mgbridgeTests/FramingTests.swift`).

## Discovery

- Bonjour/mDNS service type: `_mgbridge._tcp`, ephemeral port
- Service name: the device's user-visible name (One UI device name / Mac computer name)
- TXT record: `v=1`, `fp=<first 16 hex chars of the cert SHA-256>`

The TXT fingerprint prefix only pre-filters UI candidates; TLS pinning is the gate.

## Transport

- TLS (1.3 negotiated in practice, 1.2 floor), **mutual** certificates
- Each device holds a long-lived self-signed cert (Android: AndroidKeyStore EC P-256;
  Mac: openssl-minted P-256 imported via `SecPKCS12Import`)
- Trust = exact pinning: SHA-256 of the peer's leaf DER ∈ the local paired-peer store.
  No CAs, no hostname checks, no expiry semantics
- Both devices run a listener; whichever side is sending connects as the client
- One TCP connection per session, torn down after `receipt` / `pairOk` / `clip`

## Framing

Control frames: `len:uint32 big-endian` + `len` bytes of UTF-8 JSON, `len ≤ 1 MiB`.
File payloads: exactly `size` raw bytes, unframed, immediately after their `file` frame.
Parsers must be incremental — TCP/TLS delivers arbitrary chunk boundaries.

Every frame carries a `t` discriminator:

| frame | fields | notes |
|---|---|---|
| `hello` | `v`, `name` | always the client's first frame |
| `pairReq` | `name`, `proof` | see Pairing |
| `pairOk` | `name` | server's name; both sides persist the peer |
| `pairErr` | `reason` | |
| `offer` | `files: [{name, size, mime}]` | |
| `accept` / `reject` | — / `reason` | auto-`accept` for paired peers, always |
| `file` | `i` | followed by exactly `files[i].size` raw bytes |
| `done` | `sha256: [hex…]` | lowercase, order matches `offer` |
| `receipt` | `ok: [bool…]` | receiver's verdict after hashing |
| `clip` | `text` | clipboard push (Mac → phone) |
| `bye` | — | polite close |
| `err` | `reason` | fatal; close after sending |

Rules:

- Server drops the connection if the client cert fingerprint is untrusted, **unless**
  a pairing window is armed and the first post-`hello` frame is `pairReq`
- Receiver hashes incrementally while writing; verdicts go in `receipt`
- Offered filenames are sanitized (path separators, `..`, NUL) before touching disk;
  collisions get ` (1)`-style suffixes (Mac) / MediaStore auto-rename (Android)

## Pairing (one-time, per device pair)

1. Mac menu → *Pair New Device…* → shows an 8-char Crockford-base32 code and arms a
   2-minute window (unknown certs may complete the TLS handshake during it)
2. Phone: *Pair with a Mac* → picks the Mac from mDNS, user types the code
3. Phone connects, sends `hello` then
   `pairReq{proof = HMAC-SHA256(key=token, msg=clientFpHex + serverFpHex)}` where the
   fingerprints are the lowercase-hex SHA-256 of the certs **seen in this handshake**
   — a relay MITM presents different certs, so its proof cannot match
4. Mac verifies (constant-time), persists `{name, clientFp}`, replies `pairOk`,
   disarms; phone persists `{name, serverFp}`
5. Token entry is forgiving: case-insensitive, `O→0`, `I/L→1`, separators stripped

## Sessions

```
send:   hello → offer → accept → (file i → bytes)×N → done → receipt → bye
pair:   hello → pairReq → pairOk | pairErr
clip:   hello → clip → bye
```

## Deliberate non-features (v1)

- No resume on drop — a failed file is re-sent whole; `receipt` makes failure explicit
- No app-level chunk framing — TCP+TLS already flow-control; buffers are 1 MiB
- No guest/unpaired receive (Quick Share interop) — different trust model, out of scope
