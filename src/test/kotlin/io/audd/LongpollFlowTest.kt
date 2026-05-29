package io.audd

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for the [LongpollPoll]-based longpoll API.
 *
 * Covers:
 * 1. `Streams.longpoll(...)` demuxes wire envelopes onto the typed `matches`
 *    and `notifications` flows; `since_time` advances across iterations.
 * 2. `Streams.longpoll(...)` propagates non-2xx HTTP failures onto the
 *    `errors` flow as [AudDServerException], then completes.
 * 3. `LongpollConsumer.iterate()` shares the same shape (no token, no preflight).
 */
class LongpollFlowTest {

    private fun resultEnvelope(radioId: Long, ts: Long, artist: String, title: String): String =
        """
        {
          "status": "success",
          "result": {
            "radio_id": $radioId,
            "timestamp": "2020-04-13 10:31:43",
            "play_length": 111,
            "results": [
              {"artist": "$artist", "title": "$title", "score": 100, "song_link": "https://lis.tn/x"}
            ]
          },
          "timestamp": $ts
        }
        """.trimIndent()

    private fun notificationEnvelope(radioId: Long, ts: Long): String =
        """
        {
          "status": "-",
          "notification": {
            "radio_id": $radioId,
            "stream_running": false,
            "notification_code": 650,
            "notification_message": "Recognition failed: can't connect to the audiostream"
          },
          "time": 1587939136,
          "timestamp": $ts
        }
        """.trimIndent()

    @Test
    fun `Streams longpoll demuxes match and notification, advances since_time`() = runBlocking {
        // Three calls to /longpoll/: result, notification, then we cancel via close().
        val sinceTimesObserved = mutableListOf<String?>()
        var longpollHits = 0
        val mock = MockEngine { request ->
            val isLongpoll = request.url.encodedPath.contains("/longpoll")
            val body = if (isLongpoll) {
                sinceTimesObserved += request.url.parameters["since_time"]
                longpollHits += 1
                when (longpollHits) {
                    1 -> resultEnvelope(radioId = 7L, ts = 1001L, artist = "Alan Walker", title = "Live Fast")
                    2 -> notificationEnvelope(radioId = 7L, ts = 1002L)
                    // After two events we keep returning timeouts so the producer
                    // doesn't error before we close the poll.
                    else -> """{"timeout":"yes","timestamp":${1000L + longpollHits}}"""
                }
            } else {
                """{"status":"success","result":"https://example.com/cb"}"""
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        AudD(apiToken = "test", engine = mock).use { client ->
            val poll = client.streams.longpoll(category = "abc", LongpollOptions(timeoutSeconds = 1))
            try {
                // Collect one match and one notification, then bail.
                val match = poll.matches.first()
                val notif = poll.notifications.first()
                assertEquals(7L, match.radioId)
                assertEquals("Alan Walker", match.song?.artist)
                assertEquals(650, notif.notificationCode)
            } finally {
                poll.close()
            }
        }
        // First call has no since_time; subsequent calls forward each envelope's `timestamp`.
        assertTrue(
            sinceTimesObserved.isNotEmpty() && sinceTimesObserved[0] == null,
            "first since_time must be null, observed: $sinceTimesObserved",
        )
        assertTrue(
            sinceTimesObserved.contains("1001"),
            "expected 1001 to be forwarded as since_time, observed: $sinceTimesObserved",
        )
    }

    @Test
    fun `Streams longpoll propagates non-2xx as AudDServerException on the errors flow`() = runBlocking {
        val mock = MockEngine { request ->
            val isLongpoll = request.url.encodedPath.contains("/longpoll")
            if (isLongpoll) {
                respond(
                    content = ByteReadChannel("Bad Request"),
                    status = HttpStatusCode.BadRequest,
                    headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                )
            } else {
                respond(
                    content = ByteReadChannel("""{"status":"success","result":"https://example.com/cb"}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        AudD(apiToken = "test", engine = mock, maxRetries = 1).use { client ->
            val poll = client.streams.longpoll(category = "abc", LongpollOptions(timeoutSeconds = 1))
            try {
                val err = poll.errors.first()
                assertTrue(err is AudDServerException, "expected AudDServerException, got $err")
                err as AudDServerException
                assertEquals(400, err.httpStatus)
                assertNotNull(err.message)
                assertTrue(err.message!!.contains("HTTP 400"), "message must mention HTTP status: ${err.message}")
            } finally {
                poll.close()
            }
        }
    }

    @Test
    fun `Streams longpoll(radioId) derives category and opens a subscription`() = runBlocking {
        val expectedCategory = deriveLongpollCategory("test", 42L)
        val categoriesObserved = mutableListOf<String?>()
        var longpollHits = 0
        val mock = MockEngine { request ->
            val isLongpoll = request.url.encodedPath.contains("/longpoll")
            val body = if (isLongpoll) {
                categoriesObserved += request.url.parameters["category"]
                longpollHits += 1
                if (longpollHits == 1) {
                    resultEnvelope(radioId = 42L, ts = 7001L, artist = "Foo", title = "Bar")
                } else {
                    """{"timeout":"yes","timestamp":${7000L + longpollHits}}"""
                }
            } else {
                """{"status":"success","result":"https://example.com/cb"}"""
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        AudD(apiToken = "test", engine = mock).use { client ->
            // Sanity: instance method matches the free function.
            assertEquals(expectedCategory, client.streams.deriveLongpollCategory(42L))

            val poll = client.streams.longpoll(radioId = 42L, opts = LongpollOptions(timeoutSeconds = 1))
            try {
                val match = poll.matches.first()
                assertEquals(42L, match.radioId)
                assertEquals("Foo", match.song?.artist)
            } finally {
                poll.close()
            }
        }
        assertTrue(
            categoriesObserved.isNotEmpty() && categoriesObserved.all { it == expectedCategory },
            "every /longpoll/ call must use the derived category $expectedCategory, observed: $categoriesObserved",
        )
    }

    @Test
    fun `Streams longpoll(category) overload still works after radioId overload added`() = runBlocking {
        val categoriesObserved = mutableListOf<String?>()
        var longpollHits = 0
        val mock = MockEngine { request ->
            val isLongpoll = request.url.encodedPath.contains("/longpoll")
            val body = if (isLongpoll) {
                categoriesObserved += request.url.parameters["category"]
                longpollHits += 1
                if (longpollHits == 1) {
                    resultEnvelope(radioId = 3L, ts = 8001L, artist = "Z", title = "W")
                } else {
                    """{"timeout":"yes","timestamp":${8000L + longpollHits}}"""
                }
            } else {
                """{"status":"success","result":"https://example.com/cb"}"""
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        AudD(apiToken = "test", engine = mock).use { client ->
            val poll = client.streams.longpoll(category = "abc", opts = LongpollOptions(timeoutSeconds = 1))
            try {
                val match = poll.matches.first()
                assertEquals(3L, match.radioId)
            } finally {
                poll.close()
            }
        }
        assertTrue(
            categoriesObserved.isNotEmpty() && categoriesObserved.all { it == "abc" },
            "category-string overload must forward the literal category, observed: $categoriesObserved",
        )
    }

    @Test
    fun `LongpollConsumer iterate yields events via the same shape`() = runBlocking {
        var hits = 0
        val mock = MockEngine {
            hits += 1
            val body = if (hits == 1) {
                resultEnvelope(radioId = 9L, ts = 5001L, artist = "X", title = "Y")
            } else {
                """{"timeout":"yes","timestamp":${5000L + hits}}"""
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        LongpollConsumer(category = "cat", engine = mock).use { consumer ->
            val poll = consumer.iterate(LongpollOptions(timeoutSeconds = 1, skipCallbackCheck = true))
            try {
                val match = poll.matches.first()
                assertEquals(9L, match.radioId)
                assertEquals("X", match.song?.artist)
            } finally {
                poll.close()
            }
        }
    }
}
