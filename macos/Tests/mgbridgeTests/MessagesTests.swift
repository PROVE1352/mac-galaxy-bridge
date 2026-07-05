import XCTest
@testable import mgbridge

final class MessagesTests: XCTestCase {

    func testHelloCarriesProtocolVersion() throws {
        let obj = Frame.hello(v: Frame.protocolVersion, name: "Tab S11").jsonObject()
        XCTAssertEqual("hello", obj["t"] as? String)
        XCTAssertEqual(1, obj["v"] as? Int)
        XCTAssertEqual("Tab S11", obj["name"] as? String)
    }

    func testOfferPreservesLargeSizesAndUnicodeNames() throws {
        let f = Frame.offer(files: [FileMeta(name: "영상 2026-07-05.mp4", size: 3_500_000_000, mime: "video/mp4")])
        let back = try Frame.decode(f.encoded())
        guard case .offer(let files) = back else {
            return XCTFail("not an offer")
        }
        XCTAssertEqual(3_500_000_000, files[0].size)
        XCTAssertEqual("영상 2026-07-05.mp4", files[0].name)
    }

    func testUnknownTypeThrows() {
        XCTAssertThrowsError(try Frame.decode(Data(#"{"t":"nope"}"#.utf8)))
    }

    func testMissingFieldThrows() {
        XCTAssertThrowsError(try Frame.decode(Data(#"{"t":"hello","v":1}"#.utf8)))
    }

    func testEmptyOfferAndEmptyDoneSurvive() throws {
        XCTAssertEqual(Frame.offer(files: []), try Frame.decode(Data(#"{"t":"offer","files":[]}"#.utf8)))
        XCTAssertEqual(Frame.done(sha256: []), try Frame.decode(Data(#"{"t":"done","sha256":[]}"#.utf8)))
    }
}
