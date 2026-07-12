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

    // ---- wrong-typed scalar COERCION (through all four decode paths) ----
    //
    // A scalar field arriving with a convertible wrong JSON type is coerced to
    // the declared type: number↔string render, numeric-string→number,
    // float→int truncation, number→bool (!=0), and the strict bool-string
    // whitelist. Unconvertible input degrades to null — never a garbage 0.

    // Path 1: recognize (RecognitionResult).
    @Test
    fun `recognize coerces numeric-string audio_id and object artist`() {
        // audio_id is Long — a numeric string coerces; artist is String — a
        // bare number renders to its string form.
        val body = """
            {"status":"success","result":{"audio_id":"12345","artist":123,"title":"T"}}
        """.trimIndent()

        val result = decodeRecognize(200, body, null, noOpDeprecation)
        assertNotNull(result)
        assertEquals(12345L, result!!.audioId, "numeric-string audio_id coerces to Long")
        assertEquals("123", result.artist, "number artist renders to String")
        assertEquals("T", result.title)
    }

    @Test
    fun `recognize degrades non-numeric-string audio_id to null not zero`() {
        val body = """
            {"status":"success","result":{"audio_id":"abc","artist":"A"}}
        """.trimIndent()

        val result = decodeRecognize(200, body, null, noOpDeprecation)
        assertNotNull(result)
        assertNull(result!!.audioId, "non-numeric audio_id degrades to null, never garbage 0")
        assertEquals("A", result.artist)
    }

    // Path 2: enterprise (EnterpriseMatch) — Int score, Double offsets.
    @Test
    fun `enterprise coerces string and float score and object artist`() {
        val body = """
            {"status":"success","result":[
              {"offset":"0:00","songs":[
                {"score":"85","artist":123,"title":"A"},
                {"score":85.7,"title":"B"},
                {"score":"abc","title":"C"},
                {"score":{},"title":"D","artist":{}}
              ]}
            ]}
        """.trimIndent()

        val matches = decodeEnterprise(200, body, null, noOpDeprecation)
        assertEquals(4, matches.size)
        assertEquals(85, matches[0].score, "numeric-string score coerces to Int")
        assertEquals("123", matches[0].artist, "number artist renders to String")
        assertEquals(85, matches[1].score, "float score truncates to Int")
        assertNull(matches[2].score, "non-numeric-string score degrades to null")
        assertEquals("C", matches[2].title)
        assertNull(matches[3].score, "object score degrades to null")
        assertNull(matches[3].artist, "object artist degrades to null")
    }

    @Test
    fun `enterprise coerces numeric-string offsets to Double`() {
        val body = """
            {"status":"success","result":[
              {"offset":"10","songs":[
                {"title":"T","start_offset":"1500.0","end_offset":"3000"}
              ]}
            ]}
        """.trimIndent()

        val matches = decodeEnterprise(200, body, null, noOpDeprecation)
        assertEquals(1, matches.size)
        assertEquals(1500.0, matches[0].startOffset, "numeric-string start_offset coerces to Double")
        assertEquals(3000.0, matches[0].endOffset, "numeric-string end_offset coerces to Double")
    }

    // Path 3: stream callback song (StreamCallbackSong) — Int score.
    @Test
    fun `stream callback song coerces float score and object title`() {
        val body = """
            {"status":"success","result":{"radio_id":7,"results":[
              {"artist":"Kept","title":"Song","score":92.9}
            ]}}
        """.trimIndent()

        val parsed = parseCallback(body)
        assertTrue(parsed is CallbackEvent.Match)
        val song = (parsed as CallbackEvent.Match).match.song
        assertNotNull(song)
        assertEquals(92, song!!.score, "float score truncates to Int")
        assertEquals("Kept", song.artist)
    }

    // Path 4: notification (StreamCallbackNotification) — Int code, Bool running.
    @Test
    fun `notification coerces numeric-string code and string stream_running`() {
        val body = """
            {
              "status":"success",
              "time":1587939136,
              "notification":{
                "radio_id":7,
                "stream_running":"no",
                "notification_code":"42",
                "notification_message":"m"
              }
            }
        """.trimIndent()

        val parsed = parseCallback(body)
        assertTrue(parsed is CallbackEvent.Notification)
        val n = (parsed as CallbackEvent.Notification).notification
        assertEquals(42, n.notificationCode, "numeric-string notification_code coerces to Int")
        assertEquals(false, n.streamRunning, "\"no\" coerces to false")
        assertEquals("m", n.notificationMessage)
    }

    @Test
    fun `notification coerces number stream_running by non-zero`() {
        val body = """
            {"status":"success","notification":{"radio_id":7,"stream_running":1}}
        """.trimIndent()

        val parsed = parseCallback(body)
        assertTrue(parsed is CallbackEvent.Notification)
        val n = (parsed as CallbackEvent.Notification).notification
        assertEquals(true, n.streamRunning, "1 coerces to true (!= 0)")
    }

    // Bool string whitelist — both directions, plus null for unrecognized.
    @Test
    fun `bool string whitelist coerces both directions and nulls unknowns`() {
        fun running(v: String): Boolean? {
            val body =
                """{"status":"success","notification":{"radio_id":7,"stream_running":$v}}"""
            val parsed = parseCallback(body)
            assertTrue(parsed is CallbackEvent.Notification)
            return (parsed as CallbackEvent.Notification).notification.streamRunning
        }

        // truthy
        for (t in listOf("\"yes\"", "\"on\"", "\"TRUE\"", "\" 1 \"", "\"true\"")) {
            assertEquals(true, running(t), "$t should coerce to true")
        }
        // falsey
        for (f in listOf("\"no\"", "\"off\"", "\"False\"", "\"0\"", "\"\"", "0")) {
            assertEquals(false, running(f), "$f should coerce to false")
        }
        // unrecognized → null
        for (u in listOf("\"maybe\"", "\"weird\"", "{}")) {
            assertNull(running(u), "$u should degrade to null")
        }
    }
}
