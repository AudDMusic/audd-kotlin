package io.audd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Contract tests against the canonical OpenAPI fixture set
 * (src/test/resources/fixtures, copied from audd-openapi/fixtures/).
 */
class ContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixture(name: String): JsonObject {
        val stream = javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: fail<Nothing>("fixture not found: $name")
        val text = stream.bufferedReader().use { it.readText() }
        return json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `recognize_basic parses to a Public RecognitionMatch`() {
        val fixture = loadFixture("recognize_basic.json")
        val resultJson = fixture["result"]!!.jsonObject
        val recognition = json.decodeFromJsonElement(RecognitionResult.serializer(), resultJson)
        assertNotNull(recognition.artist)
        assertNotNull(recognition.title)
        assertTrue(recognition.toMatch() is RecognitionMatch.Public)
    }

    @Test
    fun `recognize_with_metadata carries apple_music or spotify or musicbrainz`() {
        val fixture = loadFixture("recognize_with_metadata.json")
        val resultJson = fixture["result"]!!.jsonObject
        val recognition = json.decodeFromJsonElement(RecognitionResult.serializer(), resultJson)
        assertTrue(
            recognition.appleMusic != null || recognition.spotify != null ||
                recognition.musicbrainz != null,
        )
    }

    @Test
    fun `recognize_custom_match parses to a Custom RecognitionMatch`() {
        val fixture = loadFixture("recognize_custom_match.json")
        val resultJson = fixture["result"]!!.jsonObject
        val recognition = json.decodeFromJsonElement(RecognitionResult.serializer(), resultJson)
        assertTrue(recognition.toMatch() is RecognitionMatch.Custom)
        assertNotNull(recognition.audioId)
        assertNull(recognition.artist)
    }

    @Test
    fun `enterprise_with_isrc_upc carries ISRC and UPC`() {
        val fixture = loadFixture("enterprise_with_isrc_upc.json")
        val firstChunk = (fixture["result"] as JsonArray)[0].jsonObject
        val firstSong = (firstChunk["songs"] as JsonArray)[0].jsonObject
        val match = json.decodeFromJsonElement(EnterpriseMatch.serializer(), firstSong)
        assertNotNull(match.isrc)
        assertNotNull(match.upc)
        assertTrue(match.score >= 0)
    }

    @Test
    fun `streams_callback_with_result parses to a CallbackEvent Match`() {
        val fixture = loadFixture("streams_callback_with_result.json")
        val parsed = parseCallback(fixture)
        assertTrue(parsed is CallbackEvent.Match)
        val match = (parsed as CallbackEvent.Match).match
        assertEquals(7L, match.radioId)
        // First (and only) entry of the `results` array becomes `song`.
        assertEquals("Alan Walker, A\$AP Rocky", match.song.artist)
        assertEquals("Live Fast (PUBGM)", match.song.title)
        assertTrue(match.alternatives.isEmpty())
    }

    @Test
    fun `streams_callback_with_notification parses to a CallbackEvent Notification`() {
        val fixture = loadFixture("streams_callback_with_notification.json")
        val parsed = parseCallback(fixture)
        assertTrue(parsed is CallbackEvent.Notification)
        val notif = (parsed as CallbackEvent.Notification).notification
        assertEquals(650, notif.notificationCode)
        // Outer `time` field lifted onto the typed struct.
        assertEquals(1587939136, notif.time)
    }

    @Test
    fun `deriveLongpollCategory matches the audd-python reference vector`() {
        val cat = deriveLongpollCategory("0123456789abcdef0123456789abcdef", 1)
        assertEquals("e31a31e76", cat)
    }

    @Test
    fun `addReturnToUrl appends correctly`() {
        val url = addReturnToUrl("https://x.com/cb", "apple_music,deezer")
        assertTrue(url.contains("return=apple_music"))
    }

    @Test
    fun `addReturnToUrl raises on duplicate`() {
        try {
            addReturnToUrl("https://x.com/cb?return=spotify", "apple_music")
            fail<Nothing>("expected duplicate-return exception")
        } catch (e: AudDInvalidRequestException) {
            assertTrue(e.message?.contains("already contains") ?: false)
        }
    }
}
