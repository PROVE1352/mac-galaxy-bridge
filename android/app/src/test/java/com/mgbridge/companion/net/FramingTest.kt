package com.mgbridge.companion.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.ProtocolException

class FramingTest {

    private fun roundTrip(f: Frame): Frame? {
        val out = ByteArrayOutputStream()
        Framing.writeFrame(out, f)
        return Framing.readFrame(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun roundTripsEveryFrameType() {
        val frames = listOf(
            Frame.Hello(PROTOCOL_VERSION, "Galaxy S25"),
            Frame.PairReq("Galaxy S25", "ab".repeat(32)),
            Frame.PairOk("MacBook"),
            Frame.PairErr("bad code"),
            Frame.Offer(
                listOf(
                    FileMeta("사진.jpg", 1234, "image/jpeg"),
                    FileMeta("video.mp4", 5_000_000_000L, "video/mp4")
                )
            ),
            Frame.Accept,
            Frame.Reject("busy"),
            Frame.FileHeader(1),
            Frame.Done(listOf("aa".repeat(32), "bb".repeat(32))),
            Frame.Receipt(listOf(true, false)),
            Frame.Clip("주소: 서울시 성북구"),
            Frame.Bye,
            Frame.Err("boom")
        )
        for (f in frames) assertEquals(f, roundTrip(f))
    }

    @Test
    fun wireLayoutIsBigEndianLengthPlusUtf8Json() {
        val out = ByteArrayOutputStream()
        Framing.writeFrame(out, Frame.Accept)
        val bytes = out.toByteArray()
        val body = """{"t":"accept"}""".toByteArray(Charsets.UTF_8)
        assertEquals(4 + body.size, bytes.size)
        assertEquals(0, bytes[0].toInt()); assertEquals(0, bytes[1].toInt())
        assertEquals(0, bytes[2].toInt()); assertEquals(body.size, bytes[3].toInt())
        assertEquals(String(body), String(bytes, 4, body.size, Charsets.UTF_8))
    }

    @Test
    fun decodesFrameRegardlessOfKeyOrder() {
        val body = """{"name":"MacBook","v":1,"t":"hello"}""".toByteArray(Charsets.UTF_8)
        val framed = byteArrayOf(0, 0, 0, body.size.toByte()) + body
        assertEquals(Frame.Hello(1, "MacBook"), Framing.readFrame(ByteArrayInputStream(framed)))
    }

    @Test
    fun cleanEofAtBoundaryReturnsNull() {
        assertNull(Framing.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun midFrameEofThrows() {
        val out = ByteArrayOutputStream()
        Framing.writeFrame(out, Frame.Bye)
        val truncated = out.toByteArray().copyOfRange(0, 6)
        assertThrows(EOFException::class.java) {
            Framing.readFrame(ByteArrayInputStream(truncated))
        }
    }

    @Test
    fun oversizeAndZeroLengthsRejected() {
        val big = byteArrayOf(0x7f, 0, 0, 0, 1, 2, 3)
        assertThrows(ProtocolException::class.java) {
            Framing.readFrame(ByteArrayInputStream(big))
        }
        val zero = byteArrayOf(0, 0, 0, 0)
        assertThrows(ProtocolException::class.java) {
            Framing.readFrame(ByteArrayInputStream(zero))
        }
    }

    // Shared vectors — the Swift tests assert the same digests.
    @Test
    fun sha256Vectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Framing.sha256Hex(ByteArray(0))
        )
        assertEquals(
            "c3d07cf782e9503ca7a21bafc9992718b98bda916e9b7582e50c243af27d2545",
            Framing.sha256Hex("hello mgbridge".toByteArray(Charsets.UTF_8))
        )
        assertEquals(
            "40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880",
            Framing.sha256Hex(ByteArray(256) { it.toByte() })
        )
    }

    @Test
    fun copyStreamsExactlySizeBytesAndHashes() {
        val payload = ByteArray(256) { it.toByte() }
        val input = ByteArrayInputStream(payload + byteArrayOf(9, 9, 9)) // trailing bytes stay unread
        val sink = ByteArrayOutputStream()
        val hash = Framing.copy(input, sink, 256)
        assertEquals("40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880", hash)
        assertEquals(256, sink.size())
        assertEquals(3, input.available())
    }

    @Test
    fun copyThrowsOnShortPayload() {
        val input = ByteArrayInputStream(ByteArray(10))
        assertThrows(EOFException::class.java) {
            Framing.copy(input, ByteArrayOutputStream(), 11)
        }
    }

    @Test
    fun sanitizeNameStripsPathsAndNul() {
        assertEquals("evil.sh", Framing.sanitizeName("../../evil.sh"))
        assertEquals("evil.sh", Framing.sanitizeName("..\\..\\evil.sh"))
        assertEquals("photo.jpg", Framing.sanitizeName("/sdcard/DCIM/photo.jpg"))
        assertEquals("My Photo.jpg", Framing.sanitizeName("My Photo.jpg"))
        assertEquals("file", Framing.sanitizeName(".."))
        assertEquals("file", Framing.sanitizeName(""))
        assertEquals("ab.txt", Framing.sanitizeName("a\u0000b.txt"))
    }
}
