import Foundation

/// Transfer log: one JSON object per line in Application Support/history.jsonl,
/// mirroring the Android format. A torn last line is skipped on read.
enum History {

    struct Entry: Codable {
        let ts: Double // epoch seconds
        let dir: String // "in" | "out"
        let peer: String
        let name: String
        let size: Int64
        let ok: Bool
    }

    private static let maxLines = 500
    private static let lock = NSLock()

    private static var url: URL {
        AppSupport.dir.appendingPathComponent("history.jsonl")
    }

    static func append(_ entry: Entry) {
        lock.lock()
        defer { lock.unlock() }
        guard let line = try? JSONEncoder().encode(entry),
              var text = String(data: line, encoding: .utf8) else { return }
        text += "\n"
        if let handle = FileHandle(forWritingAtPath: url.path) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: Data(text.utf8))
        } else {
            try? text.write(to: url, atomically: true, encoding: .utf8)
        }
        trimIfNeeded()
    }

    static func recent(_ limit: Int = 50) -> [Entry] {
        lock.lock()
        defer { lock.unlock() }
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        let dec = JSONDecoder()
        return text.split(separator: "\n")
            .compactMap { try? dec.decode(Entry.self, from: Data($0.utf8)) }
            .suffix(limit)
            .reversed()
    }

    private static func trimIfNeeded() {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return }
        let lines = text.split(separator: "\n")
        if lines.count > maxLines {
            let kept = lines.suffix(maxLines).joined(separator: "\n") + "\n"
            try? kept.write(to: url, atomically: true, encoding: .utf8)
        }
    }

    static func humanSize(_ bytes: Int64) -> String {
        let b = Double(bytes)
        switch bytes {
        case (1 << 30)...: return String(format: "%.1f GB", b / Double(1 << 30))
        case (1 << 20)...: return String(format: "%.1f MB", b / Double(1 << 20))
        case (1 << 10)...: return String(format: "%.1f KB", b / Double(1 << 10))
        default: return "\(bytes) B"
        }
    }
}
