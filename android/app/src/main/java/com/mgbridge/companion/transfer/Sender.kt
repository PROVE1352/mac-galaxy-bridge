package com.mgbridge.companion.transfer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.mgbridge.companion.BridgeService
import com.mgbridge.companion.history.HistoryStore
import com.mgbridge.companion.net.Discovery
import com.mgbridge.companion.net.FileMeta
import com.mgbridge.companion.net.Frame
import com.mgbridge.companion.net.Framing
import com.mgbridge.companion.net.PROTOCOL_VERSION
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import javax.net.ssl.SSLSocket

/**
 * Outbound transfer session over an already-connected socket:
 * hello → offer → await accept → per-file header + raw stream → done → receipt.
 */
class Sender(private val ctx: Context) {

    class RejectedException(reason: String) : Exception(reason)

    /**
     * Sends [uris]; returns the receiver's per-file verdicts.
     * @param peerName only for the history log.
     * @throws RejectedException if the peer rejects the offer.
     */
    fun send(
        socket: SSLSocket,
        uris: List<Uri>,
        peerName: String = "?",
        onProgress: ((fileIndex: Int, sentBytes: Long, totalBytes: Long) -> Unit)? = null
    ): List<Boolean> {
        val staged = uris.map { stage(it) }
        try {
            val inp = BufferedInputStream(socket.inputStream)
            val out = BufferedOutputStream(socket.outputStream)

            Framing.writeFrame(out, Frame.Hello(PROTOCOL_VERSION, Discovery.deviceName(ctx)))
            Framing.writeFrame(out, Frame.Offer(staged.map { it.meta }))

            when (val resp = Framing.readFrame(inp)) {
                is Frame.Accept -> {}
                is Frame.Reject -> throw RejectedException(resp.reason)
                else -> throw IllegalStateException("expected accept/reject, got $resp")
            }

            socket.soTimeout = 0 // big files stream for minutes
            val hashes = ArrayList<String>(staged.size)
            staged.forEachIndexed { i, s ->
                Framing.writeFrame(out, Frame.FileHeader(i))
                s.open().use { src ->
                    hashes += Framing.copy(src, out, s.meta.size) { copied ->
                        onProgress?.invoke(i, copied, s.meta.size)
                    }
                }
                out.flush()
            }
            Framing.writeFrame(out, Frame.Done(hashes))

            val receipt = Framing.readFrame(inp)
            if (receipt !is Frame.Receipt || receipt.ok.size != staged.size) {
                throw IllegalStateException("bad receipt: $receipt")
            }
            Framing.writeFrame(out, Frame.Bye)
            staged.forEachIndexed { i, s ->
                HistoryStore.append(
                    ctx,
                    HistoryStore.Entry(
                        System.currentTimeMillis(), "out", peerName,
                        s.meta.name, s.meta.size, receipt.ok[i]
                    )
                )
            }
            Log.i(BridgeService.TAG, "sent ${receipt.ok.count { it }}/${receipt.ok.size} files")
            return receipt.ok
        } finally {
            staged.forEach { it.cleanup() }
        }
    }

    private inner class Staged(
        val meta: FileMeta,
        private val uri: Uri,
        private val tempCopy: File?
    ) {
        fun open() = tempCopy?.inputStream()
            ?: ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("cannot reopen $uri")

        fun cleanup() {
            tempCopy?.delete()
        }
    }

    /**
     * Resolves display name / size / mime for a content Uri. The wire format needs the
     * exact size up front; a provider that won't state it gets spooled to cache first.
     */
    private fun stage(uri: Uri): Staged {
        val resolver = ctx.contentResolver
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        name = Framing.sanitizeName(name)

        if (size >= 0) return Staged(FileMeta(name, size, mime), uri, null)

        val tmp = File.createTempFile("mgbridge_send", null, ctx.cacheDir)
        resolver.openInputStream(uri).use { src ->
            checkNotNull(src) { "cannot open $uri" }
            tmp.outputStream().use { dst -> src.copyTo(dst) }
        }
        return Staged(FileMeta(name, tmp.length(), mime), uri, tmp)
    }
}
