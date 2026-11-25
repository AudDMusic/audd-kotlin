package io.audd

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections

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
    fun `setApiToken propagates to the underlying http transports`(): Unit = runBlocking {
        // The token travels in the multipart form body of every POST; read it
        // back from the bodies the mock receives before and after rotating.
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val mock = MockEngine { request ->
            bodies += String(request.body.toByteArray(), Charsets.UTF_8)
            respond(
                content = ByteReadChannel("""{"status":"success","result":"https://example.com/cb"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        AudD(apiToken = "first-token", engine = mock).use { client ->
            client.streams.getCallbackUrl()
            client.setApiToken("second-token")
            client.streams.getCallbackUrl()
            client.recognize("https://audd.tech/example.mp3")
        }
        assertEquals(3, bodies.size)
        assertTrue(bodies[0].contains("first-token") && !bodies[0].contains("second-token"), bodies[0])
        assertTrue(bodies[1].contains("second-token") && !bodies[1].contains("first-token"), bodies[1])
        assertTrue(bodies[2].contains("second-token") && !bodies[2].contains("first-token"), bodies[2])
    }
}
