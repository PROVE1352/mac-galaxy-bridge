import CryptoKit
import Foundation

/// Pairing-code crypto, mirroring the Kotlin side (same test vectors):
/// `proof = HMAC-SHA256(key: token, msg: clientFpHex + serverFpHex)` over the
/// lowercase fingerprints seen in the live TLS handshake.
enum Pairing {
    /// Crockford base32: no I, L, O, U — nothing to misread.
    static let alphabet = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
    static let tokenLength = 8

    static func newToken() -> String {
        var s = ""
        for _ in 0..<tokenLength {
            s.append(alphabet[Int.random(in: 0..<alphabet.count)])
        }
        return s
    }

    /// Forgiving input: trims separators, uppercases, maps O→0 and I/L→1.
    static func normalizeToken(_ input: String) -> String {
        var out = ""
        for c in input.trimmingCharacters(in: .whitespaces).uppercased() {
            switch c {
            case " ", "-", "_": continue
            case "O": out.append("0")
            case "I", "L": out.append("1")
            default: out.append(c)
            }
        }
        return out
    }

    /// Client fingerprint first; both lowercased. Returns lowercase hex.
    static func proof(token: String, clientFp: String, serverFp: String) -> String {
        let key = SymmetricKey(data: Data(token.utf8))
        let msg = Data((clientFp.lowercased() + serverFp.lowercased()).utf8)
        let mac = HMAC<SHA256>.authenticationCode(for: msg, using: key)
        return Framing.hex(mac)
    }

    /// Constant-time comparison so a proof check can't leak by timing.
    static func proofEquals(_ a: String, _ b: String) -> Bool {
        let ab = Array(a.utf8)
        let bb = Array(b.utf8)
        guard ab.count == bb.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<ab.count {
            diff |= ab[i] ^ bb[i]
        }
        return diff == 0
    }
}
