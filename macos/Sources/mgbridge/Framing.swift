import CryptoKit
import Foundation

/// Wire framing: `len:uint32 big-endian` + UTF-8 JSON, 1 MiB cap; file payloads are
/// unframed raw bytes. Network.framework delivers arbitrary chunks, so decoding goes
/// through the incremental `FrameParser`.

enum FramingError: Error, CustomStringConvertible {
    case frameTooLarge(Int)
    case badLength(Int)

    var description: String {
        switch self {
        case .frameTooLarge(let n): return "frame too large: \(n)"
        case .badLength(let n): return "bad frame length: \(n)"
        }
    }
}

enum Framing {
    static let maxFrame = 1 << 20
    static let copyBuffer = 1 << 20

    static func encode(_ frame: Frame) throws -> Data {
        let body = try frame.encoded()
        guard body.count <= maxFrame else { throw FramingError.frameTooLarge(body.count) }
        var out = Data(capacity: 4 + body.count)
        let n = UInt32(body.count)
        out.append(UInt8((n >> 24) & 0xff))
        out.append(UInt8((n >> 16) & 0xff))
        out.append(UInt8((n >> 8) & 0xff))
        out.append(UInt8(n & 0xff))
        out.append(body)
        return out
    }

    static func sha256Hex(_ data: Data) -> String {
        hex(SHA256.hash(data: data))
    }

    static func hex<S: Sequence>(_ bytes: S) -> String where S.Element == UInt8 {
        var s = ""
        for b in bytes {
            s += String(format: "%02x", b)
        }
        return s
    }

    /// Drops any path components from an offered filename; never returns "" / "." / "..".
    static func sanitizeName(_ raw: String) -> String {
        var leaf = raw
        if let idx = leaf.lastIndex(of: "/") {
            leaf = String(leaf[leaf.index(after: idx)...])
        }
        if let idx = leaf.lastIndex(of: "\\") {
            leaf = String(leaf[leaf.index(after: idx)...])
        }
        leaf = leaf.replacingOccurrences(of: "\u{0000}", with: "")
            .trimmingCharacters(in: .whitespaces)
        if leaf.isEmpty || leaf == "." || leaf == ".." { return "file" }
        return leaf
    }
}

/// Incremental frame decoder: feed it whatever chunks arrive, pull complete frames out.
/// When the protocol switches to a raw file payload, `drain(max:)` hands back bytes the
/// parser had already buffered past the last frame.
final class FrameParser {
    private var buffer = Data()

    func append(_ data: Data) {
        buffer.append(data)
    }

    /// Returns the next complete frame, or nil if more bytes are needed.
    func nextFrame() throws -> Frame? {
        guard buffer.count >= 4 else { return nil }
        let b0 = Int(buffer[buffer.startIndex])
        let b1 = Int(buffer[buffer.index(buffer.startIndex, offsetBy: 1)])
        let b2 = Int(buffer[buffer.index(buffer.startIndex, offsetBy: 2)])
        let b3 = Int(buffer[buffer.index(buffer.startIndex, offsetBy: 3)])
        let len = (b0 << 24) | (b1 << 16) | (b2 << 8) | b3
        guard len > 0, len <= Framing.maxFrame else { throw FramingError.badLength(len) }
        guard buffer.count >= 4 + len else { return nil }
        let start = buffer.index(buffer.startIndex, offsetBy: 4)
        let end = buffer.index(start, offsetBy: len)
        let body = Data(buffer[start..<end])
        buffer.removeSubrange(buffer.startIndex..<end)
        return try Frame.decode(body)
    }

    /// Removes and returns up to `max` buffered bytes (payload mode).
    func drain(max: Int) -> Data {
        let n = Swift.min(max, buffer.count)
        guard n > 0 else { return Data() }
        let end = buffer.index(buffer.startIndex, offsetBy: n)
        let d = Data(buffer[buffer.startIndex..<end])
        buffer.removeSubrange(buffer.startIndex..<end)
        return d
    }

    var bufferedCount: Int { buffer.count }
}
