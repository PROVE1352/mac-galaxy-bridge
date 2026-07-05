import Foundation

/// Control frames of the bridge protocol (docs/PROTOCOL.md). Mirrors the Kotlin codec
/// and is tested against the same vectors. JSONSerialization (not Codable) because a
/// `t`-discriminated union maps to it more directly.

struct FileMeta: Equatable {
    let name: String
    let size: Int64
    let mime: String
}

enum FrameError: Error, CustomStringConvertible {
    case malformed(String)

    var description: String {
        switch self {
        case .malformed(let why): return "malformed frame: \(why)"
        }
    }
}

enum Frame: Equatable {
    static let protocolVersion = 1

    case hello(v: Int, name: String)
    case pairReq(name: String, proof: String)
    case pairOk(name: String)
    case pairErr(reason: String)
    case offer(files: [FileMeta])
    case accept
    case reject(reason: String)
    /// Announces that exactly `files[i].size` raw bytes follow this frame.
    case file(i: Int)
    case done(sha256: [String])
    case receipt(ok: [Bool])
    case clip(text: String)
    case bye
    case err(reason: String)

    func jsonObject() -> [String: Any] {
        switch self {
        case .hello(let v, let name):
            return ["t": "hello", "v": v, "name": name]
        case .pairReq(let name, let proof):
            return ["t": "pairReq", "name": name, "proof": proof]
        case .pairOk(let name):
            return ["t": "pairOk", "name": name]
        case .pairErr(let reason):
            return ["t": "pairErr", "reason": reason]
        case .offer(let files):
            return ["t": "offer", "files": files.map { f -> [String: Any] in
                ["name": f.name, "size": f.size, "mime": f.mime]
            }]
        case .accept:
            return ["t": "accept"]
        case .reject(let reason):
            return ["t": "reject", "reason": reason]
        case .file(let i):
            return ["t": "file", "i": i]
        case .done(let hashes):
            return ["t": "done", "sha256": hashes]
        case .receipt(let ok):
            return ["t": "receipt", "ok": ok]
        case .clip(let text):
            return ["t": "clip", "text": text]
        case .bye:
            return ["t": "bye"]
        case .err(let reason):
            return ["t": "err", "reason": reason]
        }
    }

    func encoded() throws -> Data {
        try JSONSerialization.data(withJSONObject: jsonObject(), options: [.sortedKeys])
    }

    static func decode(_ data: Data) throws -> Frame {
        guard let obj = try? JSONSerialization.jsonObject(with: data),
              let dict = obj as? [String: Any] else {
            throw FrameError.malformed("not a JSON object")
        }
        return try from(json: dict)
    }

    static func from(json obj: [String: Any]) throws -> Frame {
        func str(_ key: String) throws -> String {
            guard let s = obj[key] as? String else { throw FrameError.malformed("missing \(key)") }
            return s
        }
        func int(_ key: String) throws -> Int {
            guard let n = obj[key] as? NSNumber else { throw FrameError.malformed("missing \(key)") }
            return n.intValue
        }
        guard let t = obj["t"] as? String else { throw FrameError.malformed("missing t") }
        switch t {
        case "hello":
            return .hello(v: try int("v"), name: try str("name"))
        case "pairReq":
            return .pairReq(name: try str("name"), proof: try str("proof"))
        case "pairOk":
            return .pairOk(name: try str("name"))
        case "pairErr":
            return .pairErr(reason: try str("reason"))
        case "offer":
            guard let arr = obj["files"] as? [[String: Any]] else {
                throw FrameError.malformed("missing files")
            }
            let files = try arr.map { f -> FileMeta in
                guard let name = f["name"] as? String,
                      let size = f["size"] as? NSNumber,
                      let mime = f["mime"] as? String else {
                    throw FrameError.malformed("bad file entry")
                }
                return FileMeta(name: name, size: size.int64Value, mime: mime)
            }
            return .offer(files: files)
        case "accept":
            return .accept
        case "reject":
            return .reject(reason: try str("reason"))
        case "file":
            return .file(i: try int("i"))
        case "done":
            guard let hashes = obj["sha256"] as? [String] else {
                throw FrameError.malformed("missing sha256")
            }
            return .done(sha256: hashes)
        case "receipt":
            guard let ok = obj["ok"] as? [Bool] else {
                throw FrameError.malformed("missing ok")
            }
            return .receipt(ok: ok)
        case "clip":
            return .clip(text: try str("text"))
        case "bye":
            return .bye
        case "err":
            return .err(reason: try str("reason"))
        default:
            throw FrameError.malformed("unknown frame type \(t)")
        }
    }
}
