package com.mgbridge.companion.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * Control frames of the bridge protocol (docs/PROTOCOL.md). Every frame is a JSON
 * object with a `t` discriminator; this file is pure JVM so the codec is unit-testable
 * off-device, and the Swift side mirrors it against the same test vectors.
 */

const val PROTOCOL_VERSION = 1

data class FileMeta(val name: String, val size: Long, val mime: String)

sealed class Frame {
    data class Hello(val v: Int, val name: String) : Frame()
    data class PairReq(val name: String, val proof: String) : Frame()
    data class PairOk(val name: String) : Frame()
    data class PairErr(val reason: String) : Frame()
    data class Offer(val files: List<FileMeta>) : Frame()
    data object Accept : Frame()
    data class Reject(val reason: String) : Frame()
    /** Announces that exactly `files[i].size` raw bytes follow this frame. */
    data class FileHeader(val i: Int) : Frame()
    data class Done(val sha256: List<String>) : Frame()
    data class Receipt(val ok: List<Boolean>) : Frame()
    data class Clip(val text: String) : Frame()
    data object Bye : Frame()
    data class Err(val reason: String) : Frame()

    fun toJson(): JSONObject {
        val o = JSONObject()
        when (this) {
            is Hello -> o.put("t", "hello").put("v", v).put("name", name)
            is PairReq -> o.put("t", "pairReq").put("name", name).put("proof", proof)
            is PairOk -> o.put("t", "pairOk").put("name", name)
            is PairErr -> o.put("t", "pairErr").put("reason", reason)
            is Offer -> {
                val arr = JSONArray()
                for (f in files) arr.put(
                    JSONObject().put("name", f.name).put("size", f.size).put("mime", f.mime)
                )
                o.put("t", "offer").put("files", arr)
            }
            is Accept -> o.put("t", "accept")
            is Reject -> o.put("t", "reject").put("reason", reason)
            is FileHeader -> o.put("t", "file").put("i", i)
            is Done -> o.put("t", "done").put("sha256", JSONArray(sha256))
            is Receipt -> o.put("t", "receipt").put("ok", JSONArray(ok))
            is Clip -> o.put("t", "clip").put("text", text)
            is Bye -> o.put("t", "bye")
            is Err -> o.put("t", "err").put("reason", reason)
        }
        return o
    }

    companion object {
        /** @throws org.json.JSONException on missing fields or an unknown `t`. */
        fun fromJson(o: JSONObject): Frame = when (val t = o.getString("t")) {
            "hello" -> Hello(o.getInt("v"), o.getString("name"))
            "pairReq" -> PairReq(o.getString("name"), o.getString("proof"))
            "pairOk" -> PairOk(o.getString("name"))
            "pairErr" -> PairErr(o.getString("reason"))
            "offer" -> {
                val arr = o.getJSONArray("files")
                Offer((0 until arr.length()).map { idx ->
                    val f = arr.getJSONObject(idx)
                    FileMeta(f.getString("name"), f.getLong("size"), f.getString("mime"))
                })
            }
            "accept" -> Accept
            "reject" -> Reject(o.getString("reason"))
            "file" -> FileHeader(o.getInt("i"))
            "done" -> {
                val arr = o.getJSONArray("sha256")
                Done((0 until arr.length()).map { arr.getString(it) })
            }
            "receipt" -> {
                val arr = o.getJSONArray("ok")
                Receipt((0 until arr.length()).map { arr.getBoolean(it) })
            }
            "clip" -> Clip(o.getString("text"))
            "bye" -> Bye
            "err" -> Err(o.getString("reason"))
            else -> throw org.json.JSONException("unknown frame type: $t")
        }
    }
}
