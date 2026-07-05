import CryptoKit
import Foundation
import Network
import UniformTypeIdentifiers

/// Inbound session on the Mac: pairing requests and zero-tap receive to ~/Downloads.
///
/// Frames and raw file payloads interleave on one TLS stream; `FrameParser` buffers
/// whatever chunks arrive and `pump()` switches between control and payload modes.
final class InboundSession {

    private final class FileCtx {
        let meta: FileMeta
        let tempURL: URL
        let handle: FileHandle
        var hasher = SHA256()
        var remaining: Int64

        init(meta: FileMeta, tempURL: URL, handle: FileHandle) {
            self.meta = meta
            self.tempURL = tempURL
            self.handle = handle
            self.remaining = meta.size
        }
    }

    private enum State {
        case control
        case receiving(FileCtx)
    }

    private let connection: NWConnection
    private let parser = FrameParser()
    private var state = State.control
    private var peerName = "?"
    private var offer: [FileMeta] = []
    private var received: [(meta: FileMeta, tempURL: URL, hash: String)] = []
    private var closed = false

    var onPaired: ((String) -> Void)?
    var onClosed: ((InboundSession) -> Void)?

    init(connection: NWConnection) {
        self.connection = connection
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] st in
            guard let self else { return }
            switch st {
            case .ready:
                self.receiveLoop()
            case .failed(let error):
                NSLog("inbound session failed: \(error)")
                self.close()
            case .cancelled:
                self.discardPartials()
                self.onClosed?(self)
            default:
                break
            }
        }
        connection.start(queue: Bridge.queue)
    }

    private func receiveLoop() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: Framing.copyBuffer) {
            [weak self] data, _, isComplete, error in
            guard let self, !self.closed else { return }
            if let data, !data.isEmpty {
                self.parser.append(data)
                do {
                    try self.pump()
                } catch {
                    NSLog("inbound protocol error: \(error)")
                    self.close()
                    return
                }
            }
            if error != nil || isComplete {
                self.close()
                return
            }
            self.receiveLoop()
        }
    }

    private func pump() throws {
        while true {
            switch state {
            case .control:
                guard let frame = try parser.nextFrame() else { return }
                try handle(frame)
                if closed { return }
            case .receiving(let ctx):
                let want = ctx.remaining > Int64(Framing.copyBuffer)
                    ? Framing.copyBuffer : Int(ctx.remaining)
                let chunk = parser.drain(max: want)
                if chunk.isEmpty { return }
                ctx.hasher.update(data: chunk)
                try ctx.handle.write(contentsOf: chunk)
                ctx.remaining -= Int64(chunk.count)
                if ctx.remaining == 0 {
                    finishFile(ctx)
                    state = .control
                }
            }
        }
    }

    private func handle(_ frame: Frame) throws {
        switch frame {
        case .hello(let v, let name):
            guard v == Frame.protocolVersion else {
                send(.err(reason: "protocol v\(v) unsupported"))
                close()
                return
            }
            peerName = name

        case .pairReq(let name, let proof):
            handlePairing(name: name, proof: proof)

        case .offer(let files):
            guard files.allSatisfy({ $0.size >= 0 }) else {
                send(.reject(reason: "negative size"))
                close()
                return
            }
            offer = files
            received = []
            send(.accept)

        case .file(let i):
            guard i == received.count, i < offer.count else {
                send(.err(reason: "unexpected file index \(i)"))
                close()
                return
            }
            let ctx = try openTempFile(for: offer[i])
            if ctx.remaining == 0 {
                finishFile(ctx)
            } else {
                state = .receiving(ctx)
            }

        case .done(let hashes):
            finishTransfer(hashes: hashes)

        case .clip(let text):
            DispatchQueue.main.async {
                Clipboard.apply(text)
            }

        case .bye:
            close()

        case .err(let reason):
            NSLog("peer error: \(reason)")
            close()

        default:
            send(.err(reason: "unexpected frame"))
            close()
        }
    }

    private func handlePairing(name: String, proof: String) {
        guard let token = TrustStore.shared.currentPairingToken() else {
            send(.pairErr(reason: "pairing not armed"))
            close()
            return
        }
        guard let clientFp = Tls.peerFingerprint(of: connection) else {
            send(.pairErr(reason: "no client certificate"))
            close()
            return
        }
        let expected = Pairing.proof(
            token: token,
            clientFp: clientFp,
            serverFp: TrustStore.shared.fingerprint
        )
        if Pairing.proofEquals(expected, proof) {
            TrustStore.shared.addPeer(name: name, fp: clientFp)
            TrustStore.shared.disarmPairing()
            send(.pairOk(name: deviceName()))
            NSLog("paired with \(name) fp=\(clientFp)")
            onPaired?(name)
        } else {
            send(.pairErr(reason: "wrong code"))
            close()
        }
    }

    private func openTempFile(for meta: FileMeta) throws -> FileCtx {
        let dir = downloadsDir()
        let tempURL = dir.appendingPathComponent(".mgbridge-partial-\(UUID().uuidString)")
        FileManager.default.createFile(atPath: tempURL.path, contents: nil)
        let handle = try FileHandle(forWritingTo: tempURL)
        return FileCtx(meta: meta, tempURL: tempURL, handle: handle)
    }

    private func finishFile(_ ctx: FileCtx) {
        try? ctx.handle.close()
        let hash = Framing.hex(ctx.hasher.finalize())
        received.append((ctx.meta, ctx.tempURL, hash))
    }

    private func finishTransfer(hashes: [String]) {
        guard hashes.count == received.count, received.count == offer.count else {
            send(.err(reason: "done/offer mismatch"))
            close()
            return
        }
        var ok: [Bool] = []
        var savedNames: [String] = []
        let fm = FileManager.default
        for (i, item) in received.enumerated() {
            let match = item.hash.lowercased() == hashes[i].lowercased()
            if match {
                let dest = uniqueURL(in: downloadsDir(), name: Framing.sanitizeName(item.meta.name))
                do {
                    try fm.moveItem(at: item.tempURL, to: dest)
                    savedNames.append(dest.lastPathComponent)
                    ok.append(true)
                } catch {
                    NSLog("move failed: \(error)")
                    try? fm.removeItem(at: item.tempURL)
                    ok.append(false)
                }
            } else {
                try? fm.removeItem(at: item.tempURL)
                ok.append(false)
            }
        }
        send(.receipt(ok: ok))
        let peer = peerName
        for (i, item) in received.enumerated() {
            History.append(History.Entry(
                ts: Date().timeIntervalSince1970,
                dir: "in",
                peer: peer,
                name: Framing.sanitizeName(item.meta.name),
                size: item.meta.size,
                ok: ok[i]
            ))
        }
        received = []
        offer = []
        DispatchQueue.main.async {
            if !savedNames.isEmpty {
                Notifier.post(
                    title: "Received from \(peer)",
                    body: savedNames.joined(separator: ", ")
                )
            }
            if ok.contains(false) {
                Notifier.post(title: "Transfer problem", body: "Some files failed the hash check")
            }
        }
    }

    private func send(_ frame: Frame) {
        guard let data = try? Framing.encode(frame) else { return }
        connection.send(content: data, completion: .contentProcessed { error in
            if let error {
                NSLog("send failed: \(error)")
            }
        })
    }

    private func discardPartials() {
        if case .receiving(let ctx) = state {
            try? ctx.handle.close()
            try? FileManager.default.removeItem(at: ctx.tempURL)
            state = .control
        }
        for item in received {
            try? FileManager.default.removeItem(at: item.tempURL)
        }
        received = []
    }

    private func close() {
        guard !closed else { return }
        closed = true
        connection.cancel()
    }

    private func downloadsDir() -> URL {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
    }

    private func uniqueURL(in dir: URL, name: String) -> URL {
        var candidate = dir.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: candidate.path) else { return candidate }
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var i = 1
        while true {
            let next = ext.isEmpty ? "\(base) (\(i))" : "\(base) (\(i)).\(ext)"
            candidate = dir.appendingPathComponent(next)
            if !FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            i += 1
        }
    }
}

/// Outbound session: connect to a peer's listener, then either stream files
/// (offer → accept → payloads → done → receipt) or push the clipboard.
final class SendSession {

    enum Payload {
        case files([URL])
        case clip(String)
    }

    private let endpoint: NWEndpoint
    private let payload: Payload
    private let completion: (Result<[Bool], Error>) -> Void
    private let parser = FrameParser()
    private var connection: NWConnection!
    private var metas: [FileMeta] = []
    private var urls: [URL] = []
    private var hashes: [String] = []
    private var fileIndex = 0
    private var finished = false

    var onProgress: ((_ fileIndex: Int, _ sent: Int64, _ total: Int64) -> Void)?

    init(
        endpoint: NWEndpoint,
        payload: Payload,
        completion: @escaping (Result<[Bool], Error>) -> Void
    ) {
        self.endpoint = endpoint
        self.payload = payload
        self.completion = completion
    }

    func start() {
        connection = NWConnection(to: endpoint, using: Tls.clientParameters())
        connection.stateUpdateHandler = { [weak self] st in
            guard let self else { return }
            switch st {
            case .ready:
                self.receiveLoop()
                self.begin()
            case .failed(let error):
                self.fail(error)
            case .cancelled:
                if !self.finished {
                    self.fail(NSError(domain: "MGBridge", code: -2, userInfo: [
                        NSLocalizedDescriptionKey: "connection cancelled",
                    ]))
                }
            default:
                break
            }
        }
        connection.start(queue: Bridge.queue)
    }

    private func begin() {
        send(.hello(v: Frame.protocolVersion, name: deviceName()))
        switch payload {
        case .clip(let text):
            send(.clip(text: text))
            send(.bye)
            succeed([])

        case .files(let fileURLs):
            urls = fileURLs
            do {
                metas = try fileURLs.map { url in
                    let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
                    let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
                    let ext = url.pathExtension
                    let mime = UTType(filenameExtension: ext)?.preferredMIMEType
                        ?? "application/octet-stream"
                    return FileMeta(name: url.lastPathComponent, size: size, mime: mime)
                }
            } catch {
                fail(error)
                return
            }
            send(.offer(files: metas))
        }
    }

    private func receiveLoop() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: Framing.copyBuffer) {
            [weak self] data, _, isComplete, error in
            guard let self, !self.finished else { return }
            if let data, !data.isEmpty {
                self.parser.append(data)
                do {
                    while let frame = try self.parser.nextFrame() {
                        self.handle(frame)
                        if self.finished { return }
                    }
                } catch {
                    self.fail(error)
                    return
                }
            }
            if let error {
                self.fail(error)
                return
            }
            if isComplete {
                if !self.finished {
                    self.fail(NSError(domain: "MGBridge", code: -3, userInfo: [
                        NSLocalizedDescriptionKey: "peer closed early",
                    ]))
                }
                return
            }
            self.receiveLoop()
        }
    }

    private func handle(_ frame: Frame) {
        switch frame {
        case .accept:
            streamNext()
        case .reject(let reason):
            fail(NSError(domain: "MGBridge", code: -4, userInfo: [
                NSLocalizedDescriptionKey: "rejected: \(reason)",
            ]))
        case .receipt(let ok):
            send(.bye)
            succeed(ok)
        case .err(let reason):
            fail(NSError(domain: "MGBridge", code: -5, userInfo: [
                NSLocalizedDescriptionKey: "peer error: \(reason)",
            ]))
        default:
            break
        }
    }

    private func streamNext() {
        guard fileIndex < urls.count else {
            send(.done(sha256: hashes))
            return
        }
        let url = urls[fileIndex]
        let meta = metas[fileIndex]
        send(.file(i: fileIndex))

        let handle: FileHandle
        do {
            handle = try FileHandle(forReadingFrom: url)
        } catch {
            fail(error)
            return
        }

        var hasher = SHA256()
        var sent: Int64 = 0

        func pumpChunk() {
            if finished {
                try? handle.close()
                return
            }
            let chunk: Data
            do {
                chunk = try handle.read(upToCount: Framing.copyBuffer) ?? Data()
            } catch {
                try? handle.close()
                fail(error)
                return
            }
            if chunk.isEmpty {
                try? handle.close()
                guard sent == meta.size else {
                    fail(NSError(domain: "MGBridge", code: -6, userInfo: [
                        NSLocalizedDescriptionKey: "\(meta.name) changed size mid-send",
                    ]))
                    return
                }
                hashes.append(Framing.hex(hasher.finalize()))
                fileIndex += 1
                streamNext()
                return
            }
            hasher.update(data: chunk)
            sent += Int64(chunk.count)
            onProgress?(fileIndex, sent, meta.size)
            connection.send(content: chunk, completion: .contentProcessed { [weak self] error in
                if let error {
                    try? handle.close()
                    self?.fail(error)
                } else {
                    pumpChunk()
                }
            })
        }
        pumpChunk()
    }

    private func send(_ frame: Frame) {
        guard let data = try? Framing.encode(frame) else { return }
        connection.send(content: data, completion: .contentProcessed { [weak self] error in
            if let error {
                self?.fail(error)
            }
        })
    }

    private func succeed(_ ok: [Bool]) {
        guard !finished else { return }
        finished = true
        connection.cancel()
        completion(.success(ok))
    }

    private func fail(_ error: Error) {
        guard !finished else { return }
        finished = true
        connection?.cancel()
        completion(.failure(error))
    }
}
