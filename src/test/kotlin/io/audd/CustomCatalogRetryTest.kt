package io.audd

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * customCatalog.add is metered. A transparent retry on transport failure could
 * double-charge for the same audio fingerprinting. The SDK ships a `CRITICAL`
 * retry class that performs exactly one attempt and is independent of
 * `AudD(maxRetries = ...)` — these tests pin that contract.
 */
class CustomCatalogRetryTest {

    @Test
    fun `customCatalog add does not retry on 5xx — exactly one attempt`() = runBlocking {
        val attempts = AtomicInteger(0)
        val mock = MockEngine {
            attempts.incrementAndGet()
            respond(
                content = ByteReadChannel("""{"status":"error","error":{"error_code":300,"error_message":"server boom"}}"""),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(),
            )
        }
        // Configure maxRetries = 5 deliberately — the CRITICAL policy must override
        // it. If customCatalog.add were still using MUTATING (or any retrying class)
        // we'd see > 1 attempt.
        AudD(apiToken = "test", maxRetries = 5, engine = mock).use { client ->
            assertThrows(AudDException::class.java) {
                runBlocking {
                    client.customCatalog.add(audioId = 42L, source = Source.Url("https://audd.tech/example.mp3"))
                }
            }
        }
        assertEquals(1, attempts.get(), "customCatalog.add must perform exactly one HTTP attempt on 5xx")
    }

    @Test
    fun `customCatalog add does not retry on pre-upload connect error — exactly one attempt`() = runBlocking {
        val attempts = AtomicInteger(0)
        val mock = MockEngine {
            attempts.incrementAndGet()
            throw java.net.ConnectException("connect refused")
        }
        AudD(apiToken = "test", maxRetries = 5, engine = mock).use { client ->
            assertThrows(AudDConnectionException::class.java) {
                runBlocking {
                    client.customCatalog.add(audioId = 42L, source = Source.Url("https://audd.tech/example.mp3"))
                }
            }
        }
        assertEquals(
            1,
            attempts.get(),
            "customCatalog.add must perform exactly one HTTP attempt even on a pre-upload connect error",
        )
    }

    @Test
    fun `CRITICAL retry class never retries 5xx or any exception`() {
        // Direct, fast unit assertions on the policy primitives so a future refactor
        // can't silently flip them back to retrying.
        assertTrue(!shouldRetryResponse(500, RetryClass.CRITICAL))
        assertTrue(!shouldRetryResponse(502, RetryClass.CRITICAL))
        assertTrue(!shouldRetryResponse(503, RetryClass.CRITICAL))
        assertTrue(!shouldRetryResponse(429, RetryClass.CRITICAL))
        assertTrue(!shouldRetryResponse(408, RetryClass.CRITICAL))
        assertTrue(!shouldRetryException(java.net.ConnectException("x"), RetryClass.CRITICAL))
        assertTrue(!shouldRetryException(java.net.UnknownHostException("x"), RetryClass.CRITICAL))
        assertTrue(!shouldRetryException(java.net.SocketTimeoutException("x"), RetryClass.CRITICAL))
    }
}
