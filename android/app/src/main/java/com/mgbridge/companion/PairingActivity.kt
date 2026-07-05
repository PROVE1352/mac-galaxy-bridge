package com.mgbridge.companion

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.mgbridge.companion.net.BridgeClient
import com.mgbridge.companion.net.Discovery
import com.mgbridge.companion.net.Frame
import com.mgbridge.companion.net.Framing
import com.mgbridge.companion.net.PROTOCOL_VERSION
import com.mgbridge.companion.net.Pairing
import com.mgbridge.companion.net.TlsIdentity
import com.mgbridge.companion.net.TrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream

/**
 * One-time pairing: browse for Macs advertising _mgbridge._tcp, tap one, type the
 * 8-char code its menu bar shows. The proof HMAC binds both live TLS fingerprints,
 * so a relayed connection cannot pass.
 */
class PairingActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var discovery: Discovery
    private val peers = LinkedHashMap<String, Discovery.Peer>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Pick your Mac, then enter the code it shows"
            textSize = 18f
            setPadding(0, 0, 0, pad)
        })
        status = TextView(this).apply { text = "Searching…" }
        root.addView(status)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        val list = ListView(this).apply { adapter = this@PairingActivity.adapter }
        list.setOnItemClickListener { _, _, pos, _ ->
            peers.values.elementAtOrNull(pos)?.let { promptForCode(it) }
        }
        root.addView(list)
        setContentView(root)

        discovery = Discovery(this)
        discovery.browse(
            onPeer = { p ->
                runOnUiThread {
                    peers[p.name] = p
                    refreshList()
                }
            },
            onLost = { name ->
                runOnUiThread {
                    peers.remove(name)
                    refreshList()
                }
            }
        )
    }

    private fun refreshList() {
        adapter.clear()
        adapter.addAll(peers.values.map { "${it.name}  (${it.host.hostAddress}:${it.port})" })
        status.text = if (peers.isEmpty()) "Searching…" else "Found ${peers.size} device(s)"
    }

    private fun promptForCode(peer: Discovery.Peer) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = "8-character code"
        }
        AlertDialog.Builder(this)
            .setTitle("Pair with ${peer.name}")
            .setMessage("Enter the pairing code shown on the Mac")
            .setView(input)
            .setPositiveButton("Pair") { _, _ -> pair(peer, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pair(peer: Discovery.Peer, rawCode: String) {
        val token = Pairing.normalizeToken(rawCode)
        if (token.length != Pairing.TOKEN_LENGTH) {
            Toast.makeText(this, "Code must be ${Pairing.TOKEN_LENGTH} characters", Toast.LENGTH_SHORT).show()
            return
        }
        runOnUiThread { status.text = "Pairing with ${peer.name}…" }
        scope.launch {
            var serverFp: String? = null
            try {
                val socket = BridgeClient.connect(
                    this@PairingActivity, peer.host, peer.port,
                    pairing = true, onPeerFp = { serverFp = it }
                )
                socket.use { s ->
                    val fp = checkNotNull(serverFp) { "no server fingerprint seen" }
                    val inp = BufferedInputStream(s.inputStream)
                    val out = BufferedOutputStream(s.outputStream)
                    Framing.writeFrame(out, Frame.Hello(PROTOCOL_VERSION, Discovery.deviceName(this@PairingActivity)))
                    Framing.writeFrame(
                        out,
                        Frame.PairReq(
                            Discovery.deviceName(this@PairingActivity),
                            Pairing.proof(token, TlsIdentity.fingerprint(), fp)
                        )
                    )
                    when (val resp = Framing.readFrame(inp)) {
                        is Frame.PairOk -> {
                            TrustStore.addPeer(this@PairingActivity, resp.name, fp)
                            runOnUiThread {
                                Toast.makeText(this@PairingActivity, "Paired with ${resp.name}", Toast.LENGTH_LONG).show()
                                finish()
                            }
                        }
                        is Frame.PairErr -> showError("Pairing refused: ${resp.reason}")
                        else -> showError("Unexpected reply: $resp")
                    }
                }
            } catch (e: Exception) {
                Log.w(BridgeService.TAG, "pairing failed", e)
                showError("Pairing failed: ${e.message}")
            }
        }
    }

    private fun showError(msg: String) = runOnUiThread {
        status.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        discovery.stopBrowse()
        scope.cancel()
        super.onDestroy()
    }
}
