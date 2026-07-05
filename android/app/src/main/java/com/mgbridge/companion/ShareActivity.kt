package com.mgbridge.companion

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mgbridge.companion.transfer.PeerConnector
import com.mgbridge.companion.transfer.Sender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Share-sheet target: Photos/Files → Share → "Mac-Galaxy Bridge" → lands on the Mac.
 * Also accepts plain shared text by spooling it into a small .txt file.
 */
class ShareActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize = 16f }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(ProgressBar(this@ShareActivity))
            addView(status)
        })

        val uris = extractUris()
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        status.text = "Sending ${uris.size} item(s) to your Mac…"
        scope.launch {
            try {
                PeerConnector.connect(this@ShareActivity).use { socket ->
                    val ok = Sender(this@ShareActivity).send(socket, uris) { i, sent, total ->
                        if (total > 0) runOnUiThread {
                            status.text = "Sending ${i + 1}/${uris.size} — ${(sent * 100 / total)}%"
                        }
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this@ShareActivity,
                            if (ok.all { it }) "Sent to Mac ✔" else "Mac reported a failed file",
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                }
            } catch (e: PeerConnector.NoPeerException) {
                fail("No paired Mac on this network")
            } catch (e: Exception) {
                fail("Send failed: ${e.message}")
            }
        }
    }

    private fun extractUris(): List<Uri> {
        val streams: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra())
            Intent.ACTION_SEND_MULTIPLE -> streamListExtra()
            else -> emptyList()
        }
        if (streams.isNotEmpty()) return streams

        // Text-only share (a URL, a note) → spool to a .txt file.
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return emptyList()
        val f = File(cacheDir, "shared-${System.currentTimeMillis()}.txt").apply { writeText(text) }
        return listOf(Uri.fromFile(f))
    }

    private fun streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun streamListExtra(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else
            @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM))
            ?.filterNotNull() ?: emptyList()

    private fun fail(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
