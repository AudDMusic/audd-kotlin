package io.audd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Enterprise chunk offsets anchor each fragment in the uploaded file. The
 * decode loop adds that anchor to the fragment-relative start/end offsets so
 * callers get absolute file positions via [EnterpriseMatch.startSeconds] /
 * [EnterpriseMatch.endSeconds].
 */
class EnterpriseOffsetTest {

    private val noOpDeprecation: DeprecationCallback = { }

    @Test
    fun `chunk offset anchors per-song start and end seconds`() {
        // Two chunks: the first anchored at 00:01:00 (60 s), the second with no
        // parseable offset. startOffset 4200 ms / endOffset 11800 ms inside the
        // first fragment → 60 + 4.2 = 64.2 s and 60 + 11.8 = 71.8 s.
        val body = """
            {
              "status": "success",
              "result": [
                {
                  "offset": "00:01:00",
                  "songs": [
                    {
                      "artist": "Anchored Artist",
                      "title": "Anchored Title",
                      "start_offset": 4200,
                      "end_offset": 11800
                    }
                  ]
                },
                {
                  "songs": [
                    {
                      "artist": "Floating Artist",
                      "title": "Floating Title",
                      "start_offset": 1000,
                      "end_offset": 2000
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val matches = decodeEnterprise(200, body, null, noOpDeprecation)
        assertEquals(2, matches.size)

        val anchored = matches[0]
        assertEquals(64.2, anchored.startSeconds!!, 1e-9)
        assertEquals(71.8, anchored.endSeconds!!, 1e-9)
        // Raw fragment-relative offsets are preserved as-is.
        assertEquals(4200.0, anchored.startOffset)
        assertEquals(11800.0, anchored.endOffset)

        val floating = matches[1]
        assertNull(floating.startSeconds, "no chunk offset → startSeconds stays null")
        assertNull(floating.endSeconds, "no chunk offset → endSeconds stays null")
    }

    @Test
    fun `chunk offset present but song has no fragment offsets defaults to the anchor`() {
        val body = """
            {"status":"success","result":[{"offset":"01:30","songs":[{"artist":"A"}]}]}
        """.trimIndent()
        val matches = decodeEnterprise(200, body, null, noOpDeprecation)
        assertEquals(1, matches.size)
        // 01:30 = 90 s; missing start/end offsets count as 0.
        assertEquals(90.0, matches[0].startSeconds!!, 1e-9)
        assertEquals(90.0, matches[0].endSeconds!!, 1e-9)
    }

    @Test
    fun `offsetToSeconds parses SS, MMSS, HHMMSS, numeric, and rejects garbage`() {
        assertEquals(45.0, offsetToSeconds("45")!!, 1e-9)
        assertEquals(90.0, offsetToSeconds("01:30")!!, 1e-9)
        assertEquals(3661.0, offsetToSeconds("01:01:01")!!, 1e-9)
        assertEquals(12.5, offsetToSeconds("12.5")!!, 1e-9)
        assertNull(offsetToSeconds(null as String?))
        assertNull(offsetToSeconds(""))
        assertNull(offsetToSeconds("not-a-time"))
        assertNull(offsetToSeconds("00:00:00:00"))
    }

    @Test
    fun `default enterprise request sends accurate_offsets true`() {
        val fields = buildEnterpriseFields(
            returnStr = null,
            skip = null,
            every = null,
            limit = 1,
            skipFirstSeconds = null,
            useTimecode = null,
            accurateOffsets = null,
        )
        assertEquals("true", fields["accurate_offsets"])
    }

    @Test
    fun `enterprise request honours accurate_offsets false when opted out`() {
        val fields = buildEnterpriseFields(
            returnStr = null,
            skip = null,
            every = null,
            limit = 1,
            skipFirstSeconds = null,
            useTimecode = null,
            accurateOffsets = false,
        )
        assertEquals("false", fields["accurate_offsets"])
    }
}
