package io.audd

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for v0.2.0 item 4: `onEvent` inspection hook.
 */
class OnEventHookTest {

    @Test
    fun `onEvent emits REQUEST and RESPONSE around a successful recognize`() = runBlocking {
        val events = mutableListOf<AudDEvent>()
        val mock = MockEngine {
            respond(
                content = ByteReadChannel("""{"status":"success","result":null}"""),
                status = HttpStatusCode.OK,
                headers = headersOf("x-request-id", "req-abc"),
            )
        }
        AudD(apiToken = "test", engine = mock, onEvent = { events += it }).use { client ->
            client.recognize(Source.Url("https://audd.tech/example.mp3"))
        }
        assertEquals(2, events.size)
        assertEquals(AudDEvent.Kind.REQUEST, events[0].kind)
        assertEquals("recognize", events[0].method)
        assertEquals(AudDEvent.Kind.RESPONSE, events[1].kind)
        assertEquals("req-abc", events[1].requestId)
        assertEquals(200, events[1].httpStatus)
        assertNotNull(events[1].elapsedMs)
        assertTrue(events[1].elapsedMs!! >= 0L)
    }

    @Test
    fun `onEvent emits EXCEPTION when the network raises`() = runBlocking {
        val events = mutableListOf<AudDEvent>()
        val mock = MockEngine { throw java.net.UnknownHostException("nope") }
        val client = AudD(
            apiToken = "test",
            maxRetries = 1,
            engine = mock,
            onEvent = { events += it },
        )
        try {
            try {
                client.recognize(Source.Url("https://audd.tech/example.mp3"))
            } catch (_: Throwable) {
                // After retries are exhausted, the underlying network exception
                // (or its wrapped form) is rethrown — both paths must emit
                // an EXCEPTION event.
            }
        } finally {
            client.close()
        }
        assertTrue(events.any { it.kind == AudDEvent.Kind.REQUEST })
        assertTrue(events.any { it.kind == AudDEvent.Kind.EXCEPTION })
        // Hook never receives the api_token in extras.
        assertFalse(events.any { it.extras.values.any { v -> v.toString().contains("test") } })
    }

    @Test
    fun `onEvent hook exception is swallowed and never breaks the request path`() = runBlocking {
        val mock = MockEngine {
            respond(
                content = ByteReadChannel("""{"status":"success","result":null}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(),
            )
        }
        val crashy: (AudDEvent) -> Unit = { error("boom") }
        AudD(apiToken = "test", engine = mock, onEvent = crashy).use { client ->
            // Must NOT throw — hook errors are swallowed by runCatching.
            client.recognize(Source.Url("https://audd.tech/example.mp3"))
        }
    }
}
