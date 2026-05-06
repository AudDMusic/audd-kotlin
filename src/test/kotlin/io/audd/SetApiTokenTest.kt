package io.audd

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression tests for v0.2.0 item 3: thread-safe `setApiToken(newToken)`.
 */
class SetApiTokenTest {

    @Test
    fun `setApiToken updates the property and rejects empty input`() {
        AudD(apiToken = "old").use { client ->
            assertEquals("old", client.apiToken)
            client.setApiToken("new")
            assertEquals("new", client.apiToken)
            assertThrows(IllegalArgumentException::class.java) { client.setApiToken("") }
            assertThrows(IllegalArgumentException::class.java) { client.setApiToken("   ") }
        }
    }

    @Test
    fun `setApiToken propagates to underlying http transports via longpoll URL`() = runBlocking {
        // Longpoll uses GET with the api_token as a URL param — easiest to inspect.
        // Streams.longpoll preflights getCallbackUrl (which we satisfy with a stubbed
        // response), then issues GET /longpoll/?category=...&api_token=...
        val capturedTokens = mutableListOf<String>()
        val mock = MockEngine { request ->
            val tokenParam = request.url.parameters["api_token"]
            if (tokenParam != null) capturedTokens += tokenParam
            // For setCallbackUrl preflight + the longpoll GET, return a benign success.
            val isLongpoll = request.url.encodedPath.contains("/longpoll")
            val body = if (isLongpoll) {
                """{"timeout":"yes"}"""
            } else {
                """{"status":"success","result":"https://example.com/cb"}"""
            }
            respond(
                content = ByteReadChannel(body),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        AudD(apiToken = "first", engine = mock).use { client ->
            client.streams.longpoll(category = "abc", timeoutSeconds = 1).first()
            client.setApiToken("second")
            client.streams.longpoll(category = "abc", timeoutSeconds = 1).first()
        }
        assertTrue(capturedTokens.contains("first"), "expected 'first' in $capturedTokens")
        assertTrue(capturedTokens.contains("second"), "expected 'second' in $capturedTokens")
        // Order: 'first' must precede 'second' — rotation took effect.
        assertTrue(
            capturedTokens.indexOf("first") < capturedTokens.indexOf("second"),
            "first must precede second in $capturedTokens",
        )
    }
}
