package io.audd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A successful API response must NEVER throw because a field is absent or a
 * different type than expected. The enterprise endpoint legitimately returns
 * songs with no `score` (and no `isrc` / `upc` / `label`); decoding must
 * tolerate that and surface the missing fields as `null`.
 */
class MissingFieldToleranceTest {

    private val noOpDeprecation: DeprecationCallback = { }

    @Test
    fun `enterprise song without score decodes with score null and does not throw`() {
        // A real-shaped enterprise envelope whose single song omits score,
        // isrc, upc, and label. Before the fix this threw MissingFieldException.
        val body = """
            {
              "status": "success",
              "result": [
                {
                  "offset": "0:00",
                  "songs": [
                    {
                      "timecode": "00:15",
                      "artist": "Some Artist",
                      "title": "Some Title",
                      "album": "Some Album",
                      "release_date": "2020-01-01",
                      "song_link": "https://lis.tn/abc"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val matches = decodeEnterprise(
            httpStatus = 200,
            rawBody = body,
            requestId = null,
            onDeprecation = noOpDeprecation,
        )

        assertEquals(1, matches.size)
        val m = matches[0]
        assertNull(m.score, "score should be null when the server omits it")
        assertNull(m.isrc)
        assertNull(m.upc)
        assertNull(m.label)
        assertEquals("Some Artist", m.artist)
        assertEquals("Some Title", m.title)
    }

    @Test
    fun `enterprise song missing every optional field still decodes`() {
        val body = """
            {"status":"success","result":[{"offset":"0:00","songs":[{}]}]}
        """.trimIndent()

        val matches = decodeEnterprise(200, body, null, noOpDeprecation)
        assertEquals(1, matches.size)
        assertNull(matches[0].score)
        assertNull(matches[0].timecode)
        assertNull(matches[0].artist)
    }

    @Test
    fun `recognize result without timecode decodes without throwing`() {
        val body = """
            {"status":"success","result":{"artist":"A","title":"T"}}
        """.trimIndent()

        val result = decodeRecognize(200, body, null, noOpDeprecation)
        assertNotNull(result)
        assertNull(result!!.timecode)
        assertEquals("A", result.artist)
        assertEquals("T", result.title)
    }

    @Test
    fun `stream callback song without score and title decodes without throwing`() {
        val body = """
            {
              "status":"success",
              "time":1587939136,
              "result":{
                "radio_id":7,
                "timestamp":"2020-04-26 21:52:16",
                "results":[ {"artist":"Only Artist"} ]
              }
            }
        """.trimIndent()

        val parsed = parseCallback(body)
        assertTrue(parsed is CallbackEvent.Match)
        val match = (parsed as CallbackEvent.Match).match
        assertEquals(7L, match.radioId)
        assertNotNull(match.song)
        assertNull(match.song!!.score)
        assertNull(match.song!!.title)
        assertEquals("Only Artist", match.song!!.artist)
    }

    @Test
    fun `stream callback with empty results yields a match with null song`() {
        val body = """
            {"status":"success","result":{"radio_id":7,"results":[]}}
        """.trimIndent()

        val parsed = parseCallback(body)
        assertTrue(parsed is CallbackEvent.Match)
        val match = (parsed as CallbackEvent.Match).match
        assertEquals(7L, match.radioId)
        assertNull(match.song)
        assertTrue(match.alternatives.isEmpty())
    }

    // ---- wrong-typed field tolerance (through all four decode paths) ----
    //
    // A field arriving with the wrong JSON type on a successful response must
    // degrade to null, never throw a raw SerializationException out of a
    // public suspend method.

    @Test
    fun `recognize with wrong-typed score decodes with score-adjacent fields intact`() {
        // score is not a recognize field, but recognize results carry Int-typed
        // audio_id and Map-typed provider blocks — send audio_id as an empty
        // string (the classic score:"" shape) plus a good result.
        val body = """
            {"status":"success","result":{"audio_id":"","artist":"A","title":"T"}}
        """.trimIndent()

        val result = decodeRecognize(200, body, null, noOpDeprecation)
        assertNotNull(result)
        assertNull(result!!.audioId, "wrong-typed audio_id degrades to null")
        assertEquals("A", result.artist)
        assertEquals("T", result.title)
    }

    @Test
    fun `recognize with wrong-typed spotify block degrades that field to null`() {
        // spotify is a Map<String, JsonElement>? — a bare string is wrong-typed.
        val body = """
            {"status":"success","result":{"artist":"A","title":"T","spotify":"not-an-object"}}
        """.trimIndent()

        val result = decodeRecognize(200, body, null, noOpDeprecation)
        assertNotNull(result)
        assertNull(result!!.spotify, "wrong-typed spotify degrades to null")
        assertEquals("A", result.artist)
        assertEquals("T", result.title)
    }

    @Test
    fun `enterprise song with wrong-typed artist decodes with artist null`() {
        // artist is String? — an object is wrong-typed. score arrives as an
        // empty string. Both must degrade to null, the song must survive.
        val body = """
            {
              "status":"success",
              "result":[
                {"offset":"0:00","songs":[
                  {"artist":{},"score":"","title":"Kept Title"}
                ]}
              ]
            }
        """.trimIndent()

        val matches = decodeEnterprise(200, body, null, noOpDeprecation)
        assertEquals(1, matches.size)
        assertNull(matches[0].artist, "wrong-typed artist degrades to null")
        assertNull(matches[0].score, "wrong-typed score degrades to null")
        assertEquals("Kept Title", matches[0].title)
    }

    @Test
    fun `notification with wrong-typed notification_code degrades to null`() {
        // notification_code is Int? — a nested object is wrong-typed. The
        // notification must still parse, with the message intact.
        val body = """
            {
              "status":"success",
              "time":1587939136,
              "notification":{
                "radio_id":7,
                "notification_code":{"nested":true},
                "notification_message":"stream stopped"
              }
            }
        """.trimIndent()

        val parsed = parseCallback(body)
        assertTrue(parsed is CallbackEvent.Notification)
        val n = (parsed as CallbackEvent.Notification).notification
        assertNull(n.notificationCode, "wrong-typed notification_code degrades to null")
        assertEquals("stream stopped", n.notificationMessage)
        assertEquals(7L, n.radioId)
    }

    @Test
    fun `stream callback song with wrong-typed spotify keeps the song and drops the field`() {
        // Fourth path exercised via the longpoll/callback match decode: a
        // wrong-typed provider block degrades to null; the song is not dropped.
        val body = """
            {"status":"success","result":{"radio_id":7,"results":[
              {"artist":"Kept","title":"Song","spotify":"not-an-object"}
            ]}}
        """.trimIndent()

        val parsed = parseCallback(body)
        assertTrue(parsed is CallbackEvent.Match)
        val song = (parsed as CallbackEvent.Match).match.song
        assertNotNull(song)
        assertNull(song!!.spotify, "wrong-typed spotify degrades to null")
        assertEquals("Kept", song.artist)
        assertEquals("Song", song.title)
    }
}
