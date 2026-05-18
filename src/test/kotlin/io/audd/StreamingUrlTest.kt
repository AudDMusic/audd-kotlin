package io.audd

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for v0.2.0 item 2: `streamingUrl` / `streamingUrls` /
 * `previewUrl` on `RecognitionResult` and `EnterpriseMatch`.
 */
class StreamingUrlTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun loadFixture(name: String): JsonObject {
        val stream = javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: error("fixture not found: $name")
        val text = stream.bufferedReader().use { it.readText() }
        return json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `recognize_with_metadata exposes apple_music url and spotify external url`() {
        val fixture = loadFixture("recognize_with_metadata.json")
        val resultJson = fixture["result"]!!.jsonObject
        val recognition = json.decodeFromJsonElement(RecognitionResult.serializer(), resultJson)

        // Direct metadata URLs:
        val apple = recognition.streamingUrl(StreamingProvider.APPLE_MUSIC)
        assertNotNull(apple)
        assertTrue(apple!!.startsWith("https://music.apple.com/"))

        val spot = recognition.streamingUrl(StreamingProvider.SPOTIFY)
        assertNotNull(spot)
        assertTrue(spot!!.contains("open.spotify.com"))

        // lis.tn fallback (deezer/napster/youtube absent → fall back to song_link?provider).
        val deezer = recognition.streamingUrl(StreamingProvider.DEEZER)
        assertEquals("https://lis.tn/NbkVb?deezer", deezer)

        val youtube = recognition.streamingUrl(StreamingProvider.YOUTUBE)
        assertEquals("https://lis.tn/NbkVb?youtube", youtube)

        val all = recognition.streamingUrls()
        assertEquals(5, all.size)

        // previewUrl pulls from apple_music.previews[0].url.
        val preview = recognition.previewUrl()
        assertNotNull(preview)
        assertTrue(preview!!.contains("itunes.apple.com"))
    }

    @Test
    fun `recognize without metadata returns null streamingUrl when no song_link`() {
        val rec = RecognitionResult(timecode = "00:00")
        assertNull(rec.streamingUrl(StreamingProvider.SPOTIFY))
        assertEquals(emptyMap<StreamingProvider, String>(), rec.streamingUrls())
        assertNull(rec.previewUrl())
        assertNull(rec.thumbnailUrl)
    }

    @Test
    fun `enterprise streamingUrl is lis_tn-only`() {
        // EnterpriseMatch with a lis.tn song_link → all five providers resolve.
        val withLisTn = EnterpriseMatch(
            score = 100,
            timecode = "00:00",
            songLink = "https://lis.tn/abcDEF",
        )
        val urls = withLisTn.streamingUrls()
        assertEquals(5, urls.size)
        for (p in StreamingProvider.values()) {
            assertNotNull(withLisTn.streamingUrl(p))
        }
        assertEquals("https://lis.tn/abcDEF?spotify", withLisTn.streamingUrl(StreamingProvider.SPOTIFY))

        // Non-lis.tn song_link → empty.
        val withYouTube = EnterpriseMatch(
            score = 100,
            timecode = "00:00",
            songLink = "https://www.youtube.com/watch?v=abc",
        )
        assertEquals(emptyMap<StreamingProvider, String>(), withYouTube.streamingUrls())
        assertNull(withYouTube.streamingUrl(StreamingProvider.SPOTIFY))
    }
}
