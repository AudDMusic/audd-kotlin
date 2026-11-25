package io.audd

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Transport failures surface inside the [AudDException] hierarchy, coroutine
 * cancellation passes through untouched, and the api_token never shows up in
 * a message or a `toString()`.
 */
class TransportErrorTest {

    private val token = "secret-token-123"

    private fun okEngine(attempts: AtomicInteger = AtomicInteger()) = MockEngine {
        attempts.incrementAndGet()
        respond(
            content = ByteReadChannel("""{"status":"success","result":null}"""),
            status = HttpStatusCode.OK,
            headers = headersOf(),
        )
    }

    @Test
    fun `an unknown host on recognize surfaces as AudDConnectionException`(): Unit = runBlocking {
        val mock = MockEngine { throw java.net.UnknownHostException("api.audd.io") }
        AudD(apiToken = token, maxRetries = 1, engine = mock).use { audd ->
            val thrown = runCatching { audd.recognize("https://audd.tech/example.mp3") }.exceptionOrNull()
            assertTrue(thrown is AudDConnectionException, "got $thrown")
            assertTrue((thrown as AudDConnectionException).original is java.net.UnknownHostException)
        }
    }

    @Test
    fun `a refused connection on streams list surfaces as AudDConnectionException`(): Unit = runBlocking {
        val mock = MockEngine { throw java.net.ConnectException("Connection refused") }
        AudD(apiToken = token, maxRetries = 1, engine = mock).use { audd ->
            val thrown = runCatching { audd.streams.list() }.exceptionOrNull()
            assertTrue(thrown is AudDConnectionException, "got $thrown")
        }
    }

    @Test
    fun `withTimeout cancels recognize with TimeoutCancellationException`(): Unit = runBlocking {
        val mock = MockEngine {
            delay(10_000)
            respond(ByteReadChannel("""{"status":"success","result":null}"""), HttpStatusCode.OK, headersOf())
        }
        AudD(apiToken = token, engine = mock).use { audd ->
            val thrown = runCatching { withTimeout(200) { audd.recognize("https://audd.tech/example.mp3") } }
                .exceptionOrNull()
            assertTrue(thrown is TimeoutCancellationException, "got $thrown")
        }
    }

    @Test
    fun `maxRetries 0 still makes exactly one attempt`(): Unit = runBlocking {
        val attempts = AtomicInteger()
        AudD(apiToken = token, maxRetries = 0, engine = okEngine(attempts)).use { audd ->
            audd.recognize("https://audd.tech/example.mp3")
            audd.streams.list()
        }
        assertEquals(2, attempts.get())
    }

    @Test
    fun `a transport message carrying the token is redacted`(): Unit = runBlocking {
        val mock = MockEngine {
            throw java.io.IOException("Request timeout has expired [url=https://api.audd.io/?api_token=$token&x=1]")
        }
        AudD(apiToken = token, engine = mock).use { audd ->
            val thrown = runCatching { audd.recognize("https://audd.tech/example.mp3") }.exceptionOrNull()
            assertTrue(thrown is AudDConnectionException, "got $thrown")
            assertFalse(thrown!!.message!!.contains(token), "message: ${thrown.message}")
            assertFalse(thrown.toString().contains(token))
            assertTrue(thrown.message!!.contains("api_token=REDACTED&x=1"), "message: ${thrown.message}")
        }
    }

    @Test
    fun `toString of the client, its options, events and errors never shows the token`() {
        AudD(apiToken = token, engine = okEngine()).use { audd ->
            assertFalse(audd.toString().contains(token))
            assertFalse(audd.streams.toString().contains(token))
            assertFalse(audd.customCatalog.toString().contains(token))
        }
        LongpollConsumer(category = "abc123def", engine = okEngine()).use { consumer ->
            assertFalse(consumer.toString().contains(token))
        }
        assertFalse(LongpollOptions(sinceTime = 1L).toString().contains(token))
        assertFalse(AudDEvent(AudDEvent.Kind.REQUEST, "recognize", "${AudD.API_BASE}/").toString().contains(token))
        val apiError = errorFromResponse(
            auddJson.parseToJsonElement(
                """{"status":"error","error":{"error_code":900,"error_message":"bad"},"request_params":{"api_token":"$token"}}""",
            ) as kotlinx.serialization.json.JsonObject,
            httpStatus = 200,
            requestId = null,
        )
        assertFalse(apiError.toString().contains(token))
        assertFalse(apiError.message!!.contains(token))
    }

    @Test
    fun `Source FilePath accepts a path string`() {
        assertEquals(Source.FilePath(File("/tmp/clip.mp3")), Source.FilePath("/tmp/clip.mp3"))
    }
}
