import XCTest
@testable import mgbridge

final class FramingTests: XCTestCase {

    private func roundTrip(_ f: Frame) throws -> Frame? {
        let data = try Framing.encode(f)
        let parser = FrameParser()
        parser.append(data)
        return try parser.nextFrame()
    }

    func testRoundTripsEveryFrameType() throws {
        let frames: [Frame] = [
            .hello(v: Frame.protocolVersion, name: "MacBook"),
            .pairReq(name: "Galaxy S25", proof: String(repeating: "ab", count: 32)),
            .pairOk(name: "MacBook"),
            .pairErr(reason: "bad code"),
            .offer(files: [
                FileMeta(name: "사진.jpg", size: 1234, mime: "image/jpeg"),
                FileMeta(name: "video.mp4", size: 5_000_000_000, mime: "video/mp4"),
            ]),
            .accept,
            .reject(reason: "busy"),
            .file(i: 1),
            .done(sha256: [String(repeating: "aa", count: 32)]),
            .receipt(ok: [true, false]),
            .clip(text: "주소: 서울시 성북구"),
            .bye,
            .err(reason: "boom"),
        ]
        for f in frames {
            XCTAssertEqual(f, try roundTrip(f))
        }
    }

    func testWireLayoutIsBigEndianLengthPlusUtf8Json() throws {
        let data = try Framing.encode(.accept)
        let body = Data(#"{"t":"accept"}"#.utf8)
        XCTAssertEqual(data.count, 4 + body.count)
        XCTAssertEqual(Array(data.prefix(4)), [0, 0, 0, UInt8(body.count)])
        XCTAssertEqual(Data(data.dropFirst(4)), body)
    }

    func testDecodesFrameRegardlessOfKeyOrder() throws {
        let body = Data(#"{"name":"MacBook","v":1,"t":"hello"}"#.utf8)
        var framed = Data([0, 0, 0, UInt8(body.count)])
        framed.append(body)
        let parser = FrameParser()
        parser.append(framed)
        XCTAssertEqual(try parser.nextFrame(), .hello(v: 1, name: "MacBook"))
    }

    func testIncrementalParsingAcrossArbitraryChunks() throws {
        var stream = Data()
        stream.append(try Framing.encode(.hello(v: 1, name: "MacBook")))
        stream.append(try Framing.encode(.accept))
        stream.append(try Framing.encode(.bye))

        // Feed one byte at a time — worst-case chunking.
        let parser = FrameParser()
        var got: [Frame] = []
        for byte in stream {
            parser.append(Data([byte]))
            while let f = try parser.nextFrame() {
                got.append(f)
            }
        }
        XCTAssertEqual(got, [.hello(v: 1, name: "MacBook"), .accept, .bye])
        XCTAssertEqual(parser.bufferedCount, 0)
    }

    func testDrainHandsBackPayloadBytes() throws {
        let payload = Data([9, 8, 7, 6, 5])
        var stream = try Framing.encode(.file(i: 0))
        stream.append(payload)
        let parser = FrameParser()
        parser.append(stream)
        XCTAssertEqual(try parser.nextFrame(), .file(i: 0))
        XCTAssertEqual(parser.drain(max: 3), Data([9, 8, 7]))
        XCTAssertEqual(parser.drain(max: 100), Data([6, 5]))
        XCTAssertEqual(parser.drain(max: 100), Data())
    }

    func testOversizeAndZeroLengthsRejected() {
        let big = FrameParser()
        big.append(Data([0x7f, 0, 0, 0, 1, 2, 3]))
        XCTAssertThrowsError(try big.nextFrame())

        let zero = FrameParser()
        zero.append(Data([0, 0, 0, 0]))
        XCTAssertThrowsError(try zero.nextFrame())
    }

    // Shared vectors — the Kotlin tests assert the same digests.
    func testSha256Vectors() {
        XCTAssertEqual(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Framing.sha256Hex(Data())
        )
        XCTAssertEqual(
            "c3d07cf782e9503ca7a21bafc9992718b98bda916e9b7582e50c243af27d2545",
            Framing.sha256Hex(Data("hello mgbridge".utf8))
        )
        XCTAssertEqual(
            "40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880",
            Framing.sha256Hex(Data((0...255).map { UInt8($0) }))
        )
    }

    func testSanitizeNameStripsPathsAndNul() {
        XCTAssertEqual("evil.sh", Framing.sanitizeName("../../evil.sh"))
        XCTAssertEqual("evil.sh", Framing.sanitizeName("..\\..\\evil.sh"))
        XCTAssertEqual("photo.jpg", Framing.sanitizeName("/Users/kyuchan/photo.jpg"))
        XCTAssertEqual("My Photo.jpg", Framing.sanitizeName("My Photo.jpg"))
        XCTAssertEqual("file", Framing.sanitizeName(".."))
        XCTAssertEqual("file", Framing.sanitizeName(""))
        XCTAssertEqual("ab.txt", Framing.sanitizeName("a\u{0000}b.txt"))
    }
}
