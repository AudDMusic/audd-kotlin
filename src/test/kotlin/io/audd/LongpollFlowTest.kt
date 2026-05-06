package io.audd

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for v0.3.0: typed `Flow<LongpollEvent>` longpoll API.
 *
 * Covers:
 * 1. `Streams.longpollFlow(...)` emits successive events, advances
 *    `since_time` from each envelope's `timestamp`, and completes cleanly
 *    when the consumer cancels via `take(N)`.
 * 2. `Streams.longpollFlow(...)` propagates non-2xx HTTP errors as
 *    [AudDServerException] (consistent with [LongpollConsumer.events]).
 * 3. `LongpollConsumer.events` (property form) yields typed events with the
 *    same shape and shares the underlying loop.
 */
class LongpollFlowTest {

    @Test
    fun `Streams longpollFlow emits typed events, advances since_time, cancels via take`() = runBlocking {
        // Each call to /longpoll/ returns a different envelope so we can verify
        // the Flow yields more than one and stops cleanly at take(N).
        val sinceTimesObserved = mutableListOf<String?>()
        var longpollHits = 0
        val mock = MockEngine { request ->
            val isLongpoll = request.url.encodedPath.contains("/longpoll")
            val body = if (isLongpoll) {
                sinceTimesObserved += request.url.parameters["since_time"]
                longpollHits += 1
                // Each envelope carries an increasing timestamp the SDK should
                // forward as `since_time` on the next iteration.
                val ts = 1000L + longpollHits
                """{"timestamp":$ts,"timeout":"yes"}"""
            } else {
                // getCallbackUrl preflight — return a benign success.
                """{"status":"success","result":"https://example.com/cb"}"""
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val collected = AudD(apiToken = "test", engine = mock).use { client ->
            client.streams.longpollFlow(category = "abc", timeoutSeconds = 1)
                .take(3)
                .toList()
        }
        assertEquals(3, collected.size, "take(3) must complete cleanly with three events")
        assertTrue(collected.all { it.isTimeout }, "fixture envelopes are timeouts")
        assertEquals(listOf(1001L, 1002L, 1003L), collected.map { it.timestamp })
        // First call has no since_time, subsequent calls forward the previous
        // envelope's timestamp.
        assertEquals(listOf(null, "1001", "1002"), sinceTimesObserved)
    }

    @Test
    fun `Streams longpollFlow propagates non-2xx as AudDServerException`() = runBlocking {
        val mock = MockEngine { request ->
            val isLongpoll = request.url.encodedPath.contains("/longpoll")
            if (isLongpoll) {
                // 4xx should bypass the READ retry class and surface as AudDServerException.
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
            val thrown = assertThrows(AudDServerException::class.java) {
                runBlocking {
                    client.streams.longpollFlow(category = "abc", timeoutSeconds = 1)
                        .take(1)
                        .toList()
                }
            }
            assertEquals(400, thrown.httpStatus)
            assertNotNull(thrown.message)
            assertTrue(thrown.message!!.contains("HTTP 400"), "message must mention HTTP status: ${thrown.message}")
        }
    }

    @Test
    fun `LongpollConsumer events property yields typed events with same shape`() = runBlocking {
        var hits = 0
        val mock = MockEngine {
            hits += 1
            val ts = 5000L + hits
            respond(
                content = ByteReadChannel("""{"timestamp":$ts,"timeout":"yes"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        LongpollConsumer(category = "cat", engine = mock).use { consumer ->
            val collected = consumer.events.take(2).toList()
            assertEquals(2, collected.size)
            assertEquals(listOf(5001L, 5002L), collected.map { it.timestamp })
            assertTrue(collected.all { it.isTimeout })
        }
    }
}
