package io.audd

import io.ktor.utils.io.errors.IOException
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

/**
 * Cost-aware retry classes.
 *
 * - [READ]: idempotent reads (streams.list, streams.getCallbackUrl).
 *   Retry on 408/429/5xx + any connection error.
 * - [RECOGNITION]: recognize / recognizeEnterprise / advanced.findLyrics.
 *   Retry on pre-upload connection failures + 5xx — DO NOT retry on
 *   read-timeout-after-upload (cost protection).
 * - [MUTATING]: streams.setCallbackUrl, streams.add, streams.delete.
 *   Retry only on pre-upload connection failures — DO NOT retry 5xx
 *   (the side effect may have happened). Server-idempotent on `radio_id`.
 * - [CRITICAL]: customCatalog.add. Never retry — custom-catalog upload is
 *   metered, and an automatic re-upload on transport failure could
 *   double-charge for the same audio fingerprinting. Transient failures
 *   surface as a clean exception; the caller decides whether to retry.
 */
public enum class RetryClass { READ, RECOGNITION, MUTATING, CRITICAL }

public data class RetryPolicy(
    val retryClass: RetryClass,
    val maxAttempts: Int = 3,
    val backoffFactor: Double = 0.5,
    val backoffMaxSeconds: Double = 30.0,
)

private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR_FLOOR = 500

internal fun shouldRetryResponse(httpStatus: Int, retryClass: RetryClass): Boolean = when (retryClass) {
    RetryClass.READ -> httpStatus == HTTP_REQUEST_TIMEOUT ||
        httpStatus == HTTP_TOO_MANY_REQUESTS ||
        httpStatus >= HTTP_SERVER_ERROR_FLOOR
    RetryClass.RECOGNITION -> httpStatus >= HTTP_SERVER_ERROR_FLOOR
    RetryClass.MUTATING -> false
    RetryClass.CRITICAL -> false
}

internal fun shouldRetryException(exc: Throwable, retryClass: RetryClass): Boolean = when (retryClass) {
    RetryClass.READ -> isConnectionError(exc)
    RetryClass.RECOGNITION -> isPreUploadConnectionError(exc)
    RetryClass.MUTATING -> isPreUploadConnectionError(exc)
    RetryClass.CRITICAL -> false
}

internal fun isConnectionError(exc: Throwable): Boolean =
    exc is IOException || exc is io.ktor.client.network.sockets.ConnectTimeoutException ||
        exc is io.ktor.client.network.sockets.SocketTimeoutException ||
        exc is java.net.SocketTimeoutException ||
        exc is java.net.ConnectException ||
        exc is java.net.UnknownHostException

internal fun isPreUploadConnectionError(exc: Throwable): Boolean =
    exc is io.ktor.client.network.sockets.ConnectTimeoutException ||
        exc is java.net.ConnectException ||
        exc is java.net.UnknownHostException

internal fun backoffDelayMillis(attempt: Int, policy: RetryPolicy): Long {
    val base = min(policy.backoffFactor * (1 shl attempt), policy.backoffMaxSeconds)
    val jitter = 0.5 + Random.nextDouble()
    return (base * jitter * 1000.0).toLong()
}

/**
 * Retry the suspending block according to [policy], inspecting both throwables
 * and HTTP responses (via [shouldRetryByResponse]).
 */
internal suspend fun <T> retry(
    policy: RetryPolicy,
    shouldRetryByResponse: (T) -> Boolean = { false },
    block: suspend () -> T,
): T {
    var lastResp: T? = null
    var lastExc: Throwable? = null
    var attempt = 0
    while (attempt < policy.maxAttempts) {
        try {
            val resp = block()
            if (!shouldRetryByResponse(resp)) return resp
            lastResp = resp
            lastExc = null
        } catch (exc: Throwable) {
            if (!shouldRetryException(exc, policy.retryClass)) throw exc
            lastExc = exc
            lastResp = null
        }
        attempt += 1
        if (attempt >= policy.maxAttempts) break
        delay(backoffDelayMillis(attempt - 1, policy))
    }
    @Suppress("UNCHECKED_CAST")
    if (lastResp != null) return lastResp as T
    throw lastExc ?: error("retry exited without response or exception")
}
