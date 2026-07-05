package com.mgbridge.companion.history

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

/** Read-only list of the last transfers. */
class HistoryActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Transfer history"
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val rows = HistoryStore.recent(this).map { e ->
            val arrow = if (e.dir == "in") "↓" else "↑"
            val status = if (e.ok) "" else "  ✗"
            "$arrow ${e.name}$status\n${e.peer} · ${humanSize(e.size)} · ${fmt.format(Date(e.ts))}"
        }.ifEmpty { listOf("No transfers yet") }
        setContentView(ListView(this).apply {
            adapter = ArrayAdapter(this@HistoryActivity, android.R.layout.simple_list_item_1, rows)
        })
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
}
