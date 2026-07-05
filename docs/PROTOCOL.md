# Wire protocol (draft, pre-M2)

Design notes for the transfer core. Everything here is subject to change until M2 lands.

## Discovery
- Bonjour/mDNS service type: `_mgbridge._tcp`
- TXT record: device name, protocol version, fingerprint prefix

## Pairing (one-time, per device)
1. Mac shows QR: `{host, port, cert fingerprint, pairing token}`
2. Phone scans, connects over TLS, both sides pin the peer certificate
3. Paired peers stored locally (Keychain / EncryptedSharedPreferences)

## Transfer
- TLS 1.3 socket, length-prefixed frames
- `OFFER {files: [{name, size, mime}]}` → auto-`ACCEPT` if peer is paired → raw streams → `DONE {sha256 per file}`
- No user interaction on the receiving side for paired peers, ever. That is the whole point.

## Open questions
- Chunk size / backpressure tuning for large videos over hotspot links
- Resume on Wi-Fi drop (content-range style offsets?)
- Whether Quick Share receive (NearDrop-style) is worth adding as a secondary listener so guests can send to the Mac without the app
