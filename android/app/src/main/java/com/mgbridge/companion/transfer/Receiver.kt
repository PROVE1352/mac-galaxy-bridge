package com.mgbridge.companion.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.mgbridge.companion.BridgeService
import com.mgbridge.companion.history.HistoryStore
import com.mgbridge.companion.net.Frame
import com.mgbridge.companion.net.Framing
import com.mgbridge.companion.net.PROTOCOL_VERSION
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import javax.net.ssl.SSLSocket

/**
 * One inbound session: hello → offer → auto-accept (the peer already proved itself in
 * the TLS handshake) → raw streams into MediaStore Downloads → done → hash check →
 * receipt. Zero user interaction — that is the whole point.
 */
class Receiver(private val ctx: Context) {

    private data class Saved(val uri: Uri, val hash: String, val displayName: String)

    fun handle(socket: SSLSocket) {
        val inp = BufferedInputStream(socket.inputStream)
        val out = BufferedOutputStream(socket.outputStream)

        val hello = Framing.readFrame(inp)
        if (hello !is Frame.Hello || hello.v != PROTOCOL_VERSION) {
            Framing.writeFrame(out, Frame.Err("expected hello v$PROTOCOL_VERSION"))
            return
        }
        Log.i(BridgeService.TAG, "session from ${hello.name}")

        while (true) {
            when (val frame = Framing.readFrame(inp) ?: return) {
                is Frame.Offer -> receiveFiles(hello.name, frame, inp, out, socket)
                is Frame.Clip -> applyClip(hello.name, frame.text)
                is Frame.Bye -> return
                else -> {
                    Framing.writeFrame(out, Frame.Err("unexpected ${frame::class.simpleName}"))
                    return
                }
            }
        }
    }

    private fun receiveFiles(
        senderName: String,
        offer: Frame.Offer,
        inp: BufferedInputStream,
        out: BufferedOutputStream,
        socket: SSLSocket
    ) {
        if (offer.files.isEmpty()) {
            Framing.writeFrame(out, Frame.Reject("empty offer"))
            return
        }
        Framing.writeFrame(out, Frame.Accept)

        // A big video takes a while; the 30s handshake timeout must not kill mid-stream reads.
        socket.soTimeout = 0

        val saved = ArrayList<Saved>(offer.files.size)
        for (i in offer.files.indices) {
            val header = Framing.readFrame(inp)
            if (header !is Frame.FileHeader || header.i != i) {
                Framing.writeFrame(out, Frame.Err("expected file $i"))
                saved.forEach { discard(it.uri) }
                return
            }
            saved += save(offer.files[i], inp)
        }

        val done = Framing.readFrame(inp)
        if (done !is Frame.Done || done.sha256.size != saved.size) {
            Framing.writeFrame(out, Frame.Err("expected done with ${saved.size} hashes"))
            saved.forEach { discard(it.uri) }
            return
        }

        val ok = saved.mapIndexed { i, s ->
            val match = s.hash.equals(done.sha256[i], ignoreCase = true)
            if (match) finalize(s.uri) else discard(s.uri)
            match
        }
        Framing.writeFrame(out, Frame.Receipt(ok))

        saved.forEachIndexed { i, s ->
            HistoryStore.append(
                ctx,
                HistoryStore.Entry(
                    System.currentTimeMillis(), "in", senderName,
                    s.displayName, offer.files[i].size, ok[i]
                )
            )
        }

        val good = ok.count { it }
        Log.i(BridgeService.TAG, "received $good/${ok.size} files from $senderName")
        if (good > 0) {
            val names = saved.filterIndexed { i, _ -> ok[i] }.joinToString(", ") { it.displayName }
            notify("Received from $senderName", names)
        }
        if (good < ok.size) notify("Transfer problem", "${ok.size - good} file(s) failed hash check")
    }

    /** Streams one file into a pending MediaStore Downloads item; returns its hash. */
    private fun save(meta: com.mgbridge.companion.net.FileMeta, inp: BufferedInputStream): Saved {
        val name = Framing.sanitizeName(meta.name)
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, meta.mime.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed for $name")
        val hash = try {
            resolver.openOutputStream(uri)!!.use { sink ->
                Framing.copy(inp, sink, meta.size)
            }
        } catch (e: Exception) {
            discard(uri)
            throw e
        }
        // MediaStore may have auto-renamed on collision — report the real name.
        val finalName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else name } ?: name
        return Saved(uri, hash, finalName)
    }

    private fun finalize(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        ctx.contentResolver.update(uri, values, null, null)
    }

    private fun discard(uri: Uri) {
        try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
    }

    /**
     * Applies a clipboard push. Background `setPrimaryClip` is silently dropped on
     * some One UI builds, so the notification carries a Copy action (an activity
     * trampoline — foreground writes are always allowed) as the fallback.
     */
    private fun applyClip(sender: String, text: String) {
        try {
            ctx.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("MGBridge", text))
        } catch (e: Exception) {
            Log.w(BridgeService.TAG, "background clipboard write failed: ${e.message}")
        }
        HistoryStore.append(
            ctx,
            HistoryStore.Entry(System.currentTimeMillis(), "in", sender, "[clipboard]", text.length.toLong(), true)
        )

        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val builder = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("Clipboard from $sender")
            .setContentText(text.take(120))
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setAutoCancel(true)
        if (text.length < 90_000) { // stay under the intent-extra size limit
            val copyIntent = android.content.Intent(ctx, com.mgbridge.companion.CopyClipActivity::class.java)
                .putExtra(com.mgbridge.companion.CopyClipActivity.EXTRA_TEXT, text)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = android.app.PendingIntent.getActivity(
                ctx, text.hashCode(), copyIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(Notification.Action.Builder(null, "Copy", pi).build())
        }
        nm.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun notify(title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), n)
    }

    companion object {
        private const val CHANNEL = "mgbridge_transfers"
    }
}
