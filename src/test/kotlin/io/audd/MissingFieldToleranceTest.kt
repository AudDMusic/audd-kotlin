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
}
