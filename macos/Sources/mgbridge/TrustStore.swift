import Foundation
import Security

/// The Mac's TLS identity and the paired-peer store.
///
/// Identity: a P-256 self-signed cert minted once by the system openssl (LibreSSL,
/// always present) into identity.p12, imported via SecPKCS12Import. The p12 passphrase
/// sits next to it with 0600 perms — this is a personal, unsandboxed build; the threat
/// model is the LAN, not the local user account.
///
/// Peers: peers.json `[{"name","fp"}]` — fingerprints are public data.
final class TrustStore {
    static let shared = TrustStore()

    struct Peer: Codable, Equatable {
        let name: String
        let fp: String
    }

    private(set) var identity: SecIdentity!
    private(set) var certificate: SecCertificate!
    private(set) var fingerprint = ""

    private let lock = NSLock()
    private var peers: [Peer] = []

    private var pairingToken: String?
    private var pairingDeadline = Date.distantPast

    private var peersURL: URL { AppSupport.dir.appendingPathComponent("peers.json") }

    // MARK: identity

    func loadOrCreateIdentity() throws {
        let dir = AppSupport.dir
        let p12URL = dir.appendingPathComponent("identity.p12")
        let passURL = dir.appendingPathComponent("identity.pass")

        if !FileManager.default.fileExists(atPath: p12URL.path) {
            try mintIdentity(p12: p12URL, pass: passURL, dir: dir)
        }

        let pass = try String(contentsOf: passURL, encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let p12Data = try Data(contentsOf: p12URL)

        var items: CFArray?
        let status = SecPKCS12Import(
            p12Data as CFData,
            [kSecImportExportPassphrase as String: pass] as CFDictionary,
            &items
        )
        guard status == errSecSuccess,
              let array = items as? [[String: Any]],
              let first = array.first,
              let anyIdentity = first[kSecImportItemIdentity as String] else {
            throw NSError(domain: "MGBridge", code: Int(status), userInfo: [
                NSLocalizedDescriptionKey: "SecPKCS12Import failed (\(status))",
            ])
        }
        let id = anyIdentity as! SecIdentity

        var cert: SecCertificate?
        guard SecIdentityCopyCertificate(id, &cert) == errSecSuccess, let cert else {
            throw NSError(domain: "MGBridge", code: -1, userInfo: [
                NSLocalizedDescriptionKey: "SecIdentityCopyCertificate failed",
            ])
        }

        identity = id
        certificate = cert
        fingerprint = Framing.sha256Hex(SecCertificateCopyData(cert) as Data)
        loadPeers()
        NSLog("MGBridge identity fp=\(fingerprint)")
    }

    private func mintIdentity(p12: URL, pass passURL: URL, dir: URL) throws {
        let keyURL = dir.appendingPathComponent("identity.key.pem")
        let certURL = dir.appendingPathComponent("identity.cert.pem")
        var passBytes = [UInt8](repeating: 0, count: 24)
        guard SecRandomCopyBytes(kSecRandomDefault, passBytes.count, &passBytes) == errSecSuccess else {
            throw NSError(domain: "MGBridge", code: -1, userInfo: [
                NSLocalizedDescriptionKey: "SecRandomCopyBytes failed",
            ])
        }
        let pass = Data(passBytes).base64EncodedString()

        let steps: [[String]] = [
            ["ecparam", "-name", "prime256v1", "-genkey", "-noout", "-out", keyURL.path],
            ["req", "-new", "-x509", "-key", keyURL.path,
             "-subj", "/CN=mgbridge-mac", "-days", "7300", "-out", certURL.path],
            ["pkcs12", "-export", "-inkey", keyURL.path, "-in", certURL.path,
             "-out", p12.path, "-passout", "pass:\(pass)"],
        ]
        for args in steps {
            let (code, output) = Shell.run("/usr/bin/openssl", args)
            guard code == 0 else {
                throw NSError(domain: "MGBridge", code: Int(code), userInfo: [
                    NSLocalizedDescriptionKey: "openssl \(args[0]) failed: \(output)",
                ])
            }
        }
        try pass.write(to: passURL, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: passURL.path)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: p12.path)
        try? FileManager.default.removeItem(at: keyURL)
    }

    // MARK: peers

    private func loadPeers() {
        guard let data = try? Data(contentsOf: peersURL),
              let list = try? JSONDecoder().decode([Peer].self, from: data) else { return }
        lock.lock(); peers = list; lock.unlock()
    }

    private func savePeersLocked() {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        if let data = try? enc.encode(peers) {
            try? data.write(to: peersURL, options: .atomic)
        }
    }

    func trustedFingerprints() -> Set<String> {
        lock.lock(); defer { lock.unlock() }
        return Set(peers.map { $0.fp })
    }

    func peerList() -> [Peer] {
        lock.lock(); defer { lock.unlock() }
        return peers
    }

    func addPeer(name: String, fp: String) {
        lock.lock(); defer { lock.unlock() }
        peers.removeAll { $0.fp == fp.lowercased() }
        peers.append(Peer(name: name, fp: fp.lowercased()))
        savePeersLocked()
    }

    func removePeer(fp: String) {
        lock.lock(); defer { lock.unlock() }
        peers.removeAll { $0.fp == fp.lowercased() }
        savePeersLocked()
    }

    // MARK: pairing window

    /// Arms a 2-minute pairing window; returns the code to show the human.
    func armPairing() -> String {
        let token = Pairing.newToken()
        lock.lock()
        pairingToken = token
        pairingDeadline = Date().addingTimeInterval(120)
        lock.unlock()
        return token
    }

    func pairingArmed() -> Bool {
        lock.lock(); defer { lock.unlock() }
        return pairingToken != nil && Date() < pairingDeadline
    }

    func currentPairingToken() -> String? {
        lock.lock(); defer { lock.unlock() }
        guard pairingToken != nil, Date() < pairingDeadline else { return nil }
        return pairingToken
    }

    func disarmPairing() {
        lock.lock()
        pairingToken = nil
        pairingDeadline = .distantPast
        lock.unlock()
    }
}
