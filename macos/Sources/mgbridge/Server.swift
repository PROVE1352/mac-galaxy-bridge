import Foundation
import Network

/// The Mac's always-on listener, advertised over Bonjour as `_mgbridge._tcp` with
/// TXT `v` and a fingerprint prefix. Sessions are retained here until they close.
final class Server {
    static let serviceType = "_mgbridge._tcp"

    private var listener: NWListener?
    private var sessions: [ObjectIdentifier: InboundSession] = [:]

    var onPaired: ((String) -> Void)?

    func start() {
        do {
            let listener = try NWListener(using: Tls.serverParameters())
            listener.service = NWListener.Service(
                name: deviceName(),
                type: Server.serviceType,
                domain: nil,
                txtRecord: Server.txtRecord()
            )
            listener.newConnectionHandler = { [weak self] connection in
                guard let self else {
                    connection.cancel()
                    return
                }
                let session = InboundSession(connection: connection)
                session.onPaired = self.onPaired
                session.onClosed = { [weak self] s in
                    self?.sessions.removeValue(forKey: ObjectIdentifier(s))
                }
                self.sessions[ObjectIdentifier(session)] = session
                session.start()
            }
            listener.stateUpdateHandler = { [weak listener] st in
                switch st {
                case .ready:
                    NSLog("listener ready on port \(listener?.port?.debugDescription ?? "?")")
                case .failed(let error):
                    NSLog("listener failed: \(error)")
                default:
                    break
                }
            }
            listener.start(queue: Bridge.queue)
            self.listener = listener
        } catch {
            NSLog("listener start error: \(error)")
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    /// Bonjour TXT wire format: length-prefixed `key=value` entries.
    private static func txtRecord() -> Data {
        var data = Data()
        let entries = [
            "v=\(Frame.protocolVersion)",
            "fp=\(TrustStore.shared.fingerprint.prefix(16))",
        ]
        for entry in entries {
            let bytes = Data(entry.utf8)
            data.append(UInt8(bytes.count))
            data.append(bytes)
        }
        return data
    }
}
