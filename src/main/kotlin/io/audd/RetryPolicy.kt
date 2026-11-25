package io.audd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
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
 * - [CRITICAL]: customCatalog.add. Never retried automatically: a failure
 *   (5xx, connection error) surfaces as a clean exception, so check it before
 *   sending the upload again (an upload to an `audio_id` that already holds a
 *   song replaces it).
 */
public enum class RetryClass { READ, RECOGNITION, MUTATING, CRITICAL }

/**
 * @property maxAttempts total attempts per call, the first one included.
 *   Values below 1 are treated as 1 (one attempt, no retries).
 */
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

// Ktor's ConnectTimeoutException extends java.net.ConnectException and its
// SocketTimeoutException extends java.net.SocketTimeoutException, so the
// java.net types cover them on every Ktor version.
internal fun isConnectionError(exc: Throwable): Boolean =
    exc is IOException ||
        exc is java.net.SocketTimeoutException ||
        exc is java.net.ConnectException ||
        exc is java.net.UnknownHostException

internal fun isPreUploadConnectionError(exc: Throwable): Boolean =
    exc is java.net.ConnectException ||
        exc is java.net.UnknownHostException

internal fun backoffDelayMillis(attempt: Int, policy: RetryPolicy): Long {
    val base = min(policy.backoffFactor * (1 shl attempt), policy.backoffMaxSeconds)
    val jitter = 0.5 + Random.nextDouble()
    return (base * jitter * 1000.0).toLong()
}

private val TOKEN_IN_TEXT = Regex("(api_token=)[^&\\s\\]\\[,;\"'<>]+", RegexOption.IGNORE_CASE)

/** Replace any `api_token=<value>` in [text] (a URL inside an exception message, say). */
internal fun redactToken(text: String): String = TOKEN_IN_TEXT.replace(text, "$1REDACTED")

/**
 * Map a transport failure onto the SDK's exception hierarchy: an
 * [AudDException] passes through, anything else becomes an
 * [AudDConnectionException] whose message never carries the api_token.
 */
internal fun asAudDException(exc: Throwable): AudDException =
    exc as? AudDException ?: AudDConnectionException(redactToken(exc.message ?: exc.toString()), original = exc)

/**
 * Run one HTTP attempt. Coroutine cancellation and SDK exceptions pass
 * through; a failure [retryClass] retries is rethrown as-is for [retry] to
 * handle; any other failure becomes an [AudDConnectionException].
 */
internal inline fun <T> transportAttempt(retryClass: RetryClass, block: () -> T): T =
    try {
        block()
    } catch (exc: CancellationException) {
        throw exc
    } catch (exc: AudDException) {
        throw exc
    } catch (exc: Throwable) {
        if (shouldRetryException(exc, retryClass)) throw exc
        throw asAudDException(exc)
    }

/**
 * Retry the suspending block according to [policy], inspecting both throwables
 * and HTTP responses (via [shouldRetryByResponse]).
 *
 * Coroutine cancellation is never retried or wrapped. When the last attempt
 * fails with a transport error, that error is thrown as an
 * [AudDConnectionException].
 */
internal suspend fun <T> retry(
    policy: RetryPolicy,
    shouldRetryByResponse: (T) -> Boolean = { false },
    block: suspend () -> T,
): T {
    val maxAttempts = policy.maxAttempts.coerceAtLeast(1)
    var lastResp: T? = null
    var lastExc: Throwable? = null
    var attempt = 0
    while (attempt < maxAttempts) {
        try {
            val resp = block()
            if (!shouldRetryByResponse(resp)) return resp
            lastResp = resp
            lastExc = null
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Throwable) {
            if (!shouldRetryException(exc, policy.retryClass)) throw exc
            lastExc = exc
            lastResp = null
        }
        attempt += 1
        if (attempt >= maxAttempts) break
        delay(backoffDelayMillis(attempt - 1, policy))
    }
    @Suppress("UNCHECKED_CAST")
    if (lastResp != null) return lastResp as T
    throw asAudDException(lastExc ?: IllegalStateException("retry exited without response or exception"))
}
