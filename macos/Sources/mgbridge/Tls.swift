import Foundation
import Network
import Security

/// NWParameters builders for both directions, with exact-certificate pinning.
///
/// Trust = fingerprint ∈ TrustStore (or an armed pairing window, server side only).
/// The verify block never blanket-approves; it computes the peer leaf's SHA-256 and
/// decides. After the handshake, `peerFingerprint(of:)` re-reads the negotiated cert
/// so pairing can bind the proof to what was actually presented.
enum Tls {

    static func serverParameters() -> NWParameters {
        parameters(pairingAware: true)
    }

    static func clientParameters() -> NWParameters {
        parameters(pairingAware: false)
    }

    private static func parameters(pairingAware: Bool) -> NWParameters {
        let tls = NWProtocolTLS.Options()
        let opts = tls.securityProtocolOptions

        sec_protocol_options_set_min_tls_protocol_version(opts, .TLSv12)
        if let identity = TrustStore.shared.identity,
           let secIdentity = sec_identity_create(identity) {
            sec_protocol_options_set_local_identity(opts, secIdentity)
        }
        sec_protocol_options_set_peer_authentication_required(opts, true)
        sec_protocol_options_set_verify_block(opts, { _, secTrust, complete in
            let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
                  let leaf = chain.first else {
                complete(false)
                return
            }
            let fp = Framing.sha256Hex(SecCertificateCopyData(leaf) as Data)
            let trusted = TrustStore.shared.trustedFingerprints().contains(fp)
            let pairing = pairingAware && TrustStore.shared.pairingArmed()
            complete(trusted || pairing)
        }, Bridge.queue)

        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        let params = NWParameters(tls: tls, tcp: tcp)
        return params
    }

    /// Lowercase-hex SHA-256 of the peer's leaf cert on an established connection.
    static func peerFingerprint(of connection: NWConnection) -> String? {
        guard let meta = connection.metadata(definition: NWProtocolTLS.definition)
            as? NWProtocolTLS.Metadata else { return nil }
        var fp: String?
        sec_protocol_metadata_access_peer_certificate_chain(meta.securityProtocolMetadata) { cert in
            if fp == nil {
                let secCert = sec_certificate_copy_ref(cert).takeRetainedValue()
                fp = Framing.sha256Hex(SecCertificateCopyData(secCert) as Data)
            }
        }
        return fp
    }
}

/// One serial queue for all networking state — AppKit work hops to .main explicitly.
enum Bridge {
    static let queue = DispatchQueue(label: "mgbridge.bridge")
}
