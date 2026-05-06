package io.audd

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Tokenless longpoll consumer — for browser/widget/extension use cases that
 * already know the [category] and don't carry the api_token.
 *
 * The user/server who derived the category is responsible for ensuring a
 * callback URL is set on their account (we can't preflight that without a token).
 *
 * Hardening (per spec §4.1 / patterns S5/S6):
 * - HTTP non-2xx → [AudDServerException] (no silent infinite loop)
 * - JSON decode failure → [AudDSerializationException]
 * - Retries (READ class) on 5xx + connection errors
 * - Configurable [maxAttempts] / [backoffFactor]
 * - Implements [AutoCloseable] for `use { ... }`
 */
public class LongpollConsumer(
    private val category: String,
    private val maxAttempts: Int = 3,
    private val backoffFactor: Double = 0.5,
    engine: HttpClientEngine? = null,
    httpClient: HttpClient? = null,
) : AutoCloseable {

    private val http: AudDHttp = AudDHttp(
        apiToken = "",
        timeouts = Timeouts(connectMillis = 10_000L, readMillis = 120_000L, writeMillis = 10_000L),
        engine = engine,
        httpClient = httpClient,
    )
    private val policy: RetryPolicy = RetryPolicy(RetryClass.READ, maxAttempts, backoffFactor)

    /**
     * A [Flow] of longpoll events with the default settings (no `since_time`,
     * 50-second server-side timeout). Idiomatic for coroutine consumers:
     *
     * ```kotlin
     * LongpollConsumer(category).use { c -> c.events.collect { event -> … } }
     * ```
     *
     * For non-default `sinceTime` / `timeoutSeconds`, call [events] (function form).
     */
    public val events: Flow<LongpollEvent> get() = events()

    /**
     * A [Flow] of longpoll events parameterised by [sinceTime] and
     * [timeoutSeconds]. Errors on non-2xx / non-JSON.
     */
    public fun events(sinceTime: Long? = null, timeoutSeconds: Int = 50): Flow<LongpollEvent> =
        longpollEventFlow(
            http = http,
            policy = policy,
            url = LONGPOLL_URL,
            category = category,
            sinceTime = sinceTime,
            timeoutSeconds = timeoutSeconds,
        )

    override fun close() {
        http.close()
    }

    public companion object {
        internal const val LONGPOLL_URL = "https://api.audd.io/longpoll/"
    }

    /** Builder DSL for configuring LongpollConsumer. */
    public class Builder(private val category: String) {
        public var maxAttempts: Int = 3
        public var backoffFactor: Double = 0.5
        public var engine: HttpClientEngine? = null
        public var httpClient: HttpClient? = null

        public fun build(): LongpollConsumer = LongpollConsumer(
            category = category,
            maxAttempts = maxAttempts,
            backoffFactor = backoffFactor,
            engine = engine,
            httpClient = httpClient,
        )
    }
}

/**
 * Shared longpoll loop used by both [LongpollConsumer.events] and
 * [Streams.longpollFlow]. Emits typed [LongpollEvent]s, advances `since_time`
 * across iterations, raises [AudDServerException] on non-2xx, and
 * [AudDSerializationException] on non-JSON payloads.
 *
 * Cancellation: `flow { while (true) … }` is cooperative — `take(N)` /
 * scope cancellation breaks the loop cleanly between emissions.
 */
internal fun longpollEventFlow(
    http: AudDHttp,
    policy: RetryPolicy,
    url: String,
    category: String,
    sinceTime: Long?,
    timeoutSeconds: Int,
): Flow<LongpollEvent> = flow {
    var curSince = sinceTime
    while (true) {
        val params = buildMap {
            put("category", category)
            put("timeout", timeoutSeconds.toString())
            if (curSince != null) put("since_time", curSince.toString())
        }
        val resp = retry(policy, shouldRetryByResponse = { shouldRetryResponse(it.httpStatus, RetryClass.READ) }) {
            try {
                http.get(url, params)
            } catch (exc: Throwable) {
                if (shouldRetryException(exc, RetryClass.READ)) throw exc
                throw AudDConnectionException(exc.message ?: exc.toString(), original = exc)
            }
        }
        if (resp.httpStatus >= HTTP_CLIENT_ERROR_FLOOR) {
            throw AudDServerException(
                errorCode = 0,
                serverMessage = "Longpoll endpoint returned HTTP ${resp.httpStatus}",
                httpStatus = resp.httpStatus,
                requestId = resp.requestId,
                rawResponse = null,
            )
        }
        val body: JsonObject = try {
            val element = auddJson.parseToJsonElement(resp.body)
            element as? JsonObject
                ?: throw AudDSerializationException(
                    "Longpoll response was not a JSON object",
                    rawText = resp.body,
                )
        } catch (e: SerializationException) {
            throw AudDSerializationException(
                "Failed to parse longpoll response: ${e.message}",
                rawText = resp.body,
                cause = e,
            )
        }
        val event = auddJson.decodeFromJsonElement(LongpollEvent.serializer(), body)
        emit(event)
        val ts = body["timestamp"]?.jsonPrimitive?.longOrNull
        if (ts != null) curSince = ts
    }
}
