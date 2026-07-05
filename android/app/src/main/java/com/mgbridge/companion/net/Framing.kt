package com.mgbridge.companion.net

import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ProtocolException
import java.security.MessageDigest

/**
 * Wire framing: every control frame is `len:uint32 big-endian` + `len` bytes of UTF-8
 * JSON (cap 1 MiB); file payloads are unframed raw bytes of the exact offered size.
 * Pure JVM — unit-tested against the same vectors as the Swift codec.
 */
object Framing {

    /** Frames above this are a protocol violation (control frames are tiny). */
    const val MAX_FRAME = 1 shl 20

    /** IO buffer for raw payload streaming. */
    const val COPY_BUFFER = 1 shl 20

    fun writeFrame(out: OutputStream, frame: Frame) {
        val body = frame.toJson().toString().toByteArray(Charsets.UTF_8)
        if (body.size > MAX_FRAME) throw ProtocolException("frame too large: ${body.size}")
        val hdr = byteArrayOf(
            (body.size ushr 24).toByte(),
            (body.size ushr 16).toByte(),
            (body.size ushr 8).toByte(),
            body.size.toByte()
        )
        out.write(hdr)
        out.write(body)
        out.flush()
    }

    /**
     * Reads one frame. Returns null on clean EOF at a frame boundary; throws
     * [EOFException] on mid-frame EOF and [ProtocolException] on a bad length.
     */
    fun readFrame(inp: InputStream): Frame? {
        val first = inp.read()
        if (first == -1) return null
        val hdr = ByteArray(3)
        readFully(inp, hdr, hdr.size)
        val len = (first shl 24) or
            ((hdr[0].toInt() and 0xff) shl 16) or
            ((hdr[1].toInt() and 0xff) shl 8) or
            (hdr[2].toInt() and 0xff)
        if (len <= 0 || len > MAX_FRAME) throw ProtocolException("bad frame length: $len")
        val body = ByteArray(len)
        readFully(inp, body, len)
        return Frame.fromJson(JSONObject(String(body, Charsets.UTF_8)))
    }

    /**
     * Streams exactly [size] raw bytes from [inp] to [out], hashing along the way.
     * Returns the lowercase-hex SHA-256 of the copied bytes.
     */
    fun copy(
        inp: InputStream,
        out: OutputStream,
        size: Long,
        onProgress: ((copied: Long) -> Unit)? = null
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(COPY_BUFFER)
        var remaining = size
        while (remaining > 0) {
            val want = if (remaining < buf.size) remaining.toInt() else buf.size
            val r = inp.read(buf, 0, want)
            if (r == -1) throw EOFException("payload ended with $remaining bytes missing")
            md.update(buf, 0, r)
            out.write(buf, 0, r)
            remaining -= r
            onProgress?.invoke(size - remaining)
        }
        return toHex(md.digest())
    }

    /** Drops any path components from an offered filename; never returns "" / "." / "..". */
    fun sanitizeName(raw: String): String {
        val leaf = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace("\u0000", "").trim()
        return if (leaf.isEmpty() || leaf == "." || leaf == "..") "file" else leaf
    }

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append("0123456789abcdef"[v ushr 4])
            append("0123456789abcdef"[v and 0xf])
        }
    }

    fun sha256Hex(bytes: ByteArray): String =
        toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun readFully(inp: InputStream, buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val r = inp.read(buf, off, len - off)
            if (r == -1) throw EOFException("stream ended inside a frame")
            off += r
        }
    }
}
