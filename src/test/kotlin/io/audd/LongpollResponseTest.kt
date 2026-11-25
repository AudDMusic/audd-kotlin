package io.audd

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The longpoll endpoint's three response shapes (events, timeout, error),
 * the top-level callback shape, and how the loop paces, retries and stops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LongpollResponseTest {

    private fun fixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("fixtures/$name")?.bufferedReader()?.use { it.readText() }
            ?: fail("fixture not found: $name")

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(
        content = ByteReadChannel(body),
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    /** A source that answers from [bodies] in turn, then holds the request open until cancelled. */
    private class ScriptedSource(
        private val scope: TestScope,
        private val bodies: List<String>,
    ) : LongpollSource {
        val requests = mutableListOf<Map<String, String>>()
        val requestTimes = mutableListOf<Long>()

        override suspend fun fetch(params: Map<String, String>, minTimeoutMillis: Long): HttpResult {
            requests += params
            requestTimes += scope.testScheduler.currentTime
            val body = bodies.getOrNull(requests.size - 1) ?: awaitCancellation()
            return HttpResult(200, body, null)
        }
    }

    /** A source that answers every request with the same [body]. */
    private class RepeatingSource(private val scope: TestScope, private val body: String) : LongpollSource {
        val requestTimes = mutableListOf<Long>()

        override suspend fun fetch(params: Map<String, String>, minTimeoutMillis: Long): HttpResult {
            requestTimes += scope.testScheduler.currentTime
            return HttpResult(200, body, null)
        }
    }

    // ---- response shapes, read in isolation ----

    @Test
    fun `events fixture reads as two matches in order with the largest timestamp`() {
        val body = readLongpollBody(fixture("longpoll_events.json"), 200, null) as LongpollBody.Delivery
        val matches = body.events.map { (it as CallbackEvent.Match).match }
        assertEquals(listOf("Alan Walker, A\$AP Rocky", "Tears For Fears"), matches.map { it.song?.artist })
        assertEquals(listOf("Live Fast (PUBGM)", "Everybody Wants To Rule The World"), matches.map { it.song?.title })
        assertEquals(listOf(7L, 7L), matches.map { it.radioId })
        assertEquals(1795547548112L, body.nextSince)
        assertFalse(body.unrecognized)
    }

    @Test
    fun `timeout fixture reads as nothing new with the server timestamp`() {
        val body = readLongpollBody(fixture("longpoll_no_events.json"), 200, null) as LongpollBody.Delivery
        assertTrue(body.events.isEmpty())
        assertEquals(1777901270049L, body.nextSince)
        assertFalse(body.unrecognized)
    }

    @Test
    fun `error fixture reads as an invalid-request error carrying the server text`() {
        val body = readLongpollBody(fixture("longpoll_error.json"), 200, "req-1") as LongpollBody.Failed
        val err = body.error as AudDInvalidRequestException
        assertEquals("Invalid timeout arg.  Must be 1-120.", err.serverMessage)
        assertEquals(200, err.httpStatus)
        assertEquals("req-1", err.requestId)
    }

    @Test
    fun `a status-error body maps to the typed exception for its code`() {
        val raw = """{"status":"error","error":{"error_code":900,"error_message":"bad token"}}"""
        val body = readLongpollBody(raw, 200, null) as LongpollBody.Failed
        assertTrue(body.error is AudDAuthenticationException, "got ${body.error}")
    }

    @Test
    fun `events with no song or unreadable data are skipped, string data is decoded`() {
        val song = """{"status":"success","result":{"radio_id":3,"results":[{"artist":"A","title":"T"}]}}"""
        val quoted = kotlinx.serialization.json.JsonPrimitive(song).toString()
        val raw = """
            {"events":[
              {"timestamp":10,"data":{"status":"success","result":{"radio_id":3,"results":[]}}},
              {"timestamp":"20","data":$quoted},
              {"timestamp":30,"data":"not json"},
              {"timestamp":40,"data":5},
              "not an event",
              {"timestamp":25,"data":${fixture("streams_callback_with_notification.json")}}
            ]}
        """.trimIndent()
        val body = readLongpollBody(raw, 200, null) as LongpollBody.Delivery
        assertEquals(2, body.events.size)
        val match = (body.events[0] as CallbackEvent.Match).match
        assertEquals("A", match.song?.artist)
        assertEquals(3L, match.radioId)
        assertEquals(650, (body.events[1] as CallbackEvent.Notification).notification.notificationCode)
        assertEquals(40L, body.nextSince)
    }

    @Test
    fun `an empty events list is nothing new`() {
        val body = readLongpollBody("""{"events":[]}""", 200, null) as LongpollBody.Delivery
        assertTrue(body.events.isEmpty())
        assertNull(body.nextSince)
        assertFalse(body.unrecognized)
    }

    @Test
    fun `a top-level callback body still delivers with its timestamp`() {
        val raw = """{"status":"success","result":{"radio_id":7,"results":[{"artist":"X","title":"Y"}]},"timestamp":1001}"""
        val body = readLongpollBody(raw, 200, null) as LongpollBody.Delivery
        assertEquals("X", (body.events.single() as CallbackEvent.Match).match.song?.artist)
        assertEquals(1001L, body.nextSince)
        assertTrue(body.carriedEvent)
    }

    @Test
    fun `junk bodies read as unrecognized, never as an error`() {
        for (raw in listOf("", "<html>502 Bad Gateway</html>", "[]", "{}", "null", "42", """{"foo":1}""")) {
            val body = readLongpollBody(raw, 200, null)
            assertTrue(body is LongpollBody.Delivery && body.unrecognized && body.events.isEmpty(), "body: $raw")
        }
    }

    // ---- the loop, in virtual time ----

    @Test
    fun `since_time follows the events and the timeout responses`() = runTest {
        val source = ScriptedSource(
            this,
            listOf(fixture("longpoll_events.json"), fixture("longpoll_no_events.json")),
        )
        val matches = mutableListOf<StreamCallbackMatch>()
        backgroundScope.launch { runLongpoll(source, "cat", null, 50, { matches += it }, {}) }
        runCurrent()
        assertEquals(3, source.requests.size)
        assertNull(source.requests[0]["since_time"])
        assertEquals("1795547548112", source.requests[1]["since_time"])
        assertEquals("1777901270049", source.requests[2]["since_time"])
        assertEquals(listOf("Alan Walker, A\$AP Rocky", "Tears For Fears"), matches.map { it.song?.artist })
        // Each response moved since_time, so nothing waited.
        assertEquals(listOf(0L, 0L, 0L), source.requestTimes)
    }

    @Test
    fun `an event with an empty results list does not end the subscription`() = runTest {
        val empty = """{"events":[{"timestamp":10,"data":{"status":"success","result":{"radio_id":1,"results":[]}}}]}"""
        val source = ScriptedSource(this, listOf(empty, fixture("longpoll_events.json")))
        val matches = mutableListOf<StreamCallbackMatch>()
        var ended: AudDException? = null
        backgroundScope.launch { ended = runLongpoll(source, "cat", null, 50, { matches += it }, {}) }
        runCurrent()
        assertNull(ended)
        assertEquals(2, matches.size)
        assertEquals("10", source.requests[1]["since_time"])
    }

    @Test
    fun `junk bodies back off from 1 s doubling to 30 s instead of spinning`() = runTest {
        for (junk in listOf("<html>junk</html>", "", """{"events":[]}""", """{"timeout":"no events before timeout"}""")) {
            val source = RepeatingSource(this, junk)
            val job = launch { runLongpoll(source, "cat", null, 50, {}, {}) }
            advanceTimeBy(10_000)
            runCurrent()
            val start = source.requestTimes.first()
            assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), source.requestTimes.map { it - start }, "body: $junk")
            advanceTimeBy(300_000)
            val gaps = source.requestTimes.zipWithNext { a, b -> b - a }
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), gaps.take(7))
            assertTrue(gaps.all { it <= 30_000L })
            job.cancel()
        }
    }

    @Test
    fun `the wait resets once a response brings something new`() = runTest {
        val junk = "<html>junk</html>"
        val source = ScriptedSource(this, listOf(junk, junk, fixture("longpoll_no_events.json"), junk))
        backgroundScope.launch { runLongpoll(source, "cat", null, 50, {}, {}) }
        advanceTimeBy(60_000)
        // 0 → wait 1 s → 1 s → wait 2 s → 3 s (timeout body, new timestamp, no wait)
        // → 3 s (junk) → wait 1 s again → 4 s: the script is done and the request hangs.
        assertEquals(listOf(0L, 1_000L, 3_000L, 3_000L, 4_000L), source.requestTimes)
    }

    @Test
    fun `the error body ends the loop after one request`() = runTest {
        val source = RepeatingSource(this, fixture("longpoll_error.json"))
        val err = runLongpoll(source, "cat", null, 50, {}, {})
        assertEquals(1, source.requestTimes.size)
        assertEquals("Invalid timeout arg.  Must be 1-120.", (err as AudDInvalidRequestException).serverMessage)
    }

    @Test
    fun `close stops a waiting delay`() = runTest {
        val source = RepeatingSource(this, "<html>junk</html>")
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val poll = LongpollPoll.start(scope, true, source, "cat", null, 50)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, source.requestTimes.size)
        poll.close()
        advanceTimeBy(120_000)
        assertEquals(1, source.requestTimes.size)
        assertTrue(poll.matches.toList().isEmpty())
        assertTrue(poll.errors.toList().isEmpty())
    }

    @Test
    fun `an ignored notifications flow does not hold up matches`() = runTest {
        val notification = fixture("streams_callback_with_notification.json")
        val bodies = (1..200).map { i ->
            """{"events":[{"timestamp":$i,"data":$notification}]}"""
        } + fixture("longpoll_events.json")
        val source = ScriptedSource(this, bodies)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val poll = LongpollPoll.start(scope, true, source, "cat", null, 50)
        val first = poll.matches.first()
        assertEquals("Alan Walker, A\$AP Rocky", first.song?.artist)
        poll.close()
        runCurrent()
        // The newest notifications are kept for a late collector.
        assertEquals(LongpollPoll.NOTIFICATION_BUFFER, poll.notifications.toList().size)
    }

    // ---- over HTTP ----

    @Test
    fun `events fixture over HTTP delivers both songs, then since_time moves and close ends a waiting request`(): Unit =
        runBlocking {
            val hits = AtomicInteger()
            val sinceTimes = Collections.synchronizedList(mutableListOf<String?>())
            val secondRequest = CompletableDeferred<Unit>()
            val mock = MockEngine { request ->
                sinceTimes += request.url.parameters["since_time"]
                if (hits.incrementAndGet() == 1) {
                    respondJson(fixture("longpoll_events.json"))
                } else {
                    secondRequest.complete(Unit)
                    awaitCancellation()
                }
            }
            LongpollConsumer(category = "92b1cc7f0", engine = mock).use { consumer ->
                val poll = consumer.iterate()
                val got = withTimeout(5_000) { poll.matches.take(2).toList() }
                assertEquals(listOf("Alan Walker, A\$AP Rocky", "Tears For Fears"), got.map { it.song?.artist })
                assertEquals(listOf("Live Fast (PUBGM)", "Everybody Wants To Rule The World"), got.map { it.song?.title })
                assertEquals(listOf(7L, 7L), got.map { it.radioId })
                withTimeout(5_000) { secondRequest.await() }
                assertEquals(listOf(null, "1795547548112"), sinceTimes.toList())

                poll.close()
                withTimeout(5_000) {
                    assertTrue(poll.matches.toList().isEmpty())
                    assertTrue(poll.errors.toList().isEmpty())
                }
            }
            assertEquals(2, hits.get())
        }

    @Test
    fun `timeout fixture over HTTP delivers nothing and the next request carries its timestamp`(): Unit = runBlocking {
        val hits = AtomicInteger()
        val sinceTimes = Collections.synchronizedList(mutableListOf<String?>())
        val secondRequest = CompletableDeferred<Unit>()
        val mock = MockEngine { request ->
            sinceTimes += request.url.parameters["since_time"]
            if (hits.incrementAndGet() == 1) {
                respondJson(fixture("longpoll_no_events.json"))
            } else {
                secondRequest.complete(Unit)
                awaitCancellation()
            }
        }
        LongpollConsumer(category = "cat", engine = mock).use { consumer ->
            val poll = consumer.iterate()
            withTimeout(5_000) { secondRequest.await() }
            assertEquals(listOf(null, "1777901270049"), sinceTimes.toList())
            poll.close()
            withTimeout(5_000) { assertTrue(poll.matches.toList().isEmpty()) }
        }
    }

    @Test
    fun `error fixture over HTTP reaches the errors flow once, after exactly one request`(): Unit = runBlocking {
        val hits = AtomicInteger()
        val mock = MockEngine {
            hits.incrementAndGet()
            respondJson(fixture("longpoll_error.json"))
        }
        LongpollConsumer(category = "cat", engine = mock).use { consumer ->
            val poll = consumer.iterate(LongpollOptions(timeoutSeconds = 500, skipCallbackCheck = true))
            val errors = withTimeout(5_000) { poll.errors.toList() }
            val err = errors.single()
            assertTrue(err is AudDInvalidRequestException, "got $err")
            assertEquals("Invalid timeout arg.  Must be 1-120.", (err as AudDInvalidRequestException).serverMessage)
            // The producer has stopped: every flow completes.
            withTimeout(5_000) {
                assertTrue(poll.matches.toList().isEmpty())
                assertTrue(poll.notifications.toList().isEmpty())
            }
        }
        assertEquals(1, hits.get())
    }

    @Test
    fun `HTTP 5xx is retried within the budget and the subscription carries on`(): Unit = runBlocking {
        val hits = AtomicInteger()
        val mock = MockEngine {
            when (hits.incrementAndGet()) {
                1 -> respondJson("""{"status":"error"}""", HttpStatusCode.ServiceUnavailable)
                2 -> respondJson(fixture("longpoll_events.json"))
                else -> awaitCancellation()
            }
        }
        LongpollConsumer(category = "cat", backoffFactor = 0.001, engine = mock).use { consumer ->
            consumer.iterate().use { poll ->
                val got = withTimeout(5_000) { poll.matches.take(2).toList() }
                assertEquals(2, got.size)
            }
        }
    }

    @Test
    fun `the longpoll request carries no api_token and gets a timeout longer than its wait`(): Unit = runBlocking {
        val token = "secret-token-123"
        val seen = CompletableDeferred<io.ktor.client.request.HttpRequestData>()
        val mock = MockEngine { request ->
            if (request.url.encodedPath.contains("/longpoll")) {
                seen.complete(request)
                awaitCancellation()
            }
            respondJson("""{"status":"success","result":"https://example.com/cb"}""")
        }
        AudD(apiToken = token, engine = mock).use { audd ->
            val poll = audd.streams.longpoll(radioId = 5L, opts = LongpollOptions(timeoutSeconds = 90))
            val request = withTimeout(5_000) { seen.await() }
            assertNull(request.url.parameters["api_token"])
            assertFalse(request.url.toString().contains(token), "URL: ${request.url}")
            assertFalse(request.headers.entries().any { (_, values) -> values.any { it.contains(token) } })
            assertEquals(deriveLongpollCategory(token, 5L), request.url.parameters["category"])
            assertEquals("90", request.url.parameters["timeout"])
            val timeout = request.getCapabilityOrNull(HttpTimeoutCapability)
            assertNotNull(timeout)
            assertTrue(timeout!!.requestTimeoutMillis!! >= 105_000L, "request timeout: ${timeout.requestTimeoutMillis}")
            assertTrue(timeout.socketTimeoutMillis!! >= 105_000L, "socket timeout: ${timeout.socketTimeoutMillis}")

            // Closing the client ends the subscription cleanly.
            audd.close()
            withTimeout(5_000) {
                assertTrue(poll.matches.toList().isEmpty())
                assertTrue(poll.errors.toList().isEmpty())
            }
        }
    }

    @Test
    fun `closing the LongpollConsumer ends its subscriptions`(): Unit = runBlocking {
        val seen = CompletableDeferred<Unit>()
        val mock = MockEngine {
            seen.complete(Unit)
            awaitCancellation()
        }
        val consumer = LongpollConsumer(category = "cat", engine = mock)
        val poll = consumer.iterate()
        withTimeout(5_000) { seen.await() }
        consumer.close()
        withTimeout(5_000) {
            assertTrue(poll.matches.toList().isEmpty())
            assertTrue(poll.errors.toList().isEmpty())
        }
    }
}
