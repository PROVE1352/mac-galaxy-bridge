package com.mgbridge.companion.history

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Transfer log: one JSON object per line in filesDir/history.jsonl. Grep-able,
 * crash-proof (a torn last line is skipped), no database ceremony.
 */
object HistoryStore {

    data class Entry(
        val ts: Long,
        val dir: String, // "in" | "out"
        val peer: String,
        val name: String,
        val size: Long,
        val ok: Boolean
    )

    private const val FILE = "history.jsonl"
    private const val MAX_LINES = 500

    @Synchronized
    fun append(ctx: Context, entry: Entry) {
        val f = File(ctx.filesDir, FILE)
        val line = JSONObject()
            .put("ts", entry.ts)
            .put("dir", entry.dir)
            .put("peer", entry.peer)
            .put("name", entry.name)
            .put("size", entry.size)
            .put("ok", entry.ok)
            .toString()
        f.appendText(line + "\n")
        trimIfNeeded(f)
    }

    @Synchronized
    fun recent(ctx: Context, limit: Int = 50): List<Entry> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return f.readLines()
            .mapNotNull { parse(it) }
            .takeLast(limit)
            .reversed()
    }

    private fun parse(line: String): Entry? = try {
        val o = JSONObject(line)
        Entry(
            o.getLong("ts"), o.getString("dir"), o.getString("peer"),
            o.getString("name"), o.getLong("size"), o.getBoolean("ok")
        )
    } catch (_: Exception) {
        null
    }

    private fun trimIfNeeded(f: File) {
        val lines = f.readLines()
        if (lines.size > MAX_LINES) {
            f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
        }
    }
}
