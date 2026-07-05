import XCTest
@testable import mgbridge

final class PairingTests: XCTestCase {

    // Shared vectors — the Kotlin tests assert the same proofs.
    func testProofVectors() {
        XCTAssertEqual(
            "9ba8a5714f8501210228a623d0ed86cb5a5d3473f9e6d4ca1ddde49941f151fd",
            Pairing.proof(
                token: "MGBR1DGE",
                clientFp: String(repeating: "aa", count: 32),
                serverFp: String(repeating: "bb", count: 32)
            )
        )
        XCTAssertEqual(
            "a49f7672b7b20639d52747836503bac08a452dbbc79d4e16ed6b0d2c4ae1bae5",
            Pairing.proof(
                token: "7Y2KQ0ZX",
                clientFp: String(repeating: "0123456789abcdef", count: 4),
                serverFp: String(repeating: "fedcba9876543210", count: 4)
            )
        )
    }

    func testProofIsCaseInsensitiveOnFingerprintsOnly() {
        let upper = Pairing.proof(
            token: "MGBR1DGE",
            clientFp: String(repeating: "AA", count: 32),
            serverFp: String(repeating: "BB", count: 32)
        )
        let lower = Pairing.proof(
            token: "MGBR1DGE",
            clientFp: String(repeating: "aa", count: 32),
            serverFp: String(repeating: "bb", count: 32)
        )
        XCTAssertEqual(upper, lower)
        XCTAssertNotEqual(
            upper,
            Pairing.proof(
                token: "mgbr1dge",
                clientFp: String(repeating: "aa", count: 32),
                serverFp: String(repeating: "bb", count: 32)
            )
        )
    }

    func testSwappedFingerprintsChangeProof() {
        XCTAssertNotEqual(
            Pairing.proof(token: "MGBR1DGE", clientFp: "aa", serverFp: "bb"),
            Pairing.proof(token: "MGBR1DGE", clientFp: "bb", serverFp: "aa")
        )
    }

    func testTokensUseCrockfordAlphabet() {
        for _ in 0..<50 {
            let t = Pairing.newToken()
            XCTAssertEqual(Pairing.tokenLength, t.count)
            XCTAssertTrue(t.allSatisfy { Pairing.alphabet.contains($0) })
        }
    }

    func testNormalizeMapsConfusablesAndSeparators() {
        XCTAssertEqual("MGBR1DGE", Pairing.normalizeToken(" mgbr-idge "))
        XCTAssertEqual("0000", Pairing.normalizeToken("oOoO"))
        XCTAssertEqual("1111", Pairing.normalizeToken("iIlL"))
        XCTAssertEqual("AB12", Pairing.normalizeToken("ab_12"))
    }

    func testConstantTimeEqualsBehaves() {
        XCTAssertTrue(Pairing.proofEquals("abc123", "abc123"))
        XCTAssertFalse(Pairing.proofEquals("abc123", "abc124"))
        XCTAssertFalse(Pairing.proofEquals("abc", "abcd"))
    }
}
