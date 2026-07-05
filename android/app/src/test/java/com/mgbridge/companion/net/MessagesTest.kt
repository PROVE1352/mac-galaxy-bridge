package com.mgbridge.companion.net

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MessagesTest {

    @Test
    fun helloCarriesProtocolVersion() {
        val o = Frame.Hello(PROTOCOL_VERSION, "Tab S11").toJson()
        assertEquals("hello", o.getString("t"))
        assertEquals(1, o.getInt("v"))
        assertEquals("Tab S11", o.getString("name"))
    }

    @Test
    fun offerPreservesLargeSizesAndUnicodeNames() {
        val f = Frame.Offer(listOf(FileMeta("영상 2026-07-05.mp4", 3_500_000_000L, "video/mp4")))
        val back = Frame.fromJson(JSONObject(f.toJson().toString())) as Frame.Offer
        assertEquals(3_500_000_000L, back.files[0].size)
        assertEquals("영상 2026-07-05.mp4", back.files[0].name)
    }

    @Test
    fun unknownTypeThrows() {
        assertThrows(JSONException::class.java) {
            Frame.fromJson(JSONObject("""{"t":"nope"}"""))
        }
    }

    @Test
    fun missingFieldThrows() {
        assertThrows(JSONException::class.java) {
            Frame.fromJson(JSONObject("""{"t":"hello","v":1}"""))
        }
    }

    @Test
    fun emptyOfferAndEmptyDoneSurvive() {
        assertEquals(Frame.Offer(emptyList()), Frame.fromJson(JSONObject("""{"t":"offer","files":[]}""")))
        assertEquals(Frame.Done(emptyList()), Frame.fromJson(JSONObject("""{"t":"done","sha256":[]}""")))
    }
}
