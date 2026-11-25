package io.audd

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/**
 * Streams namespace — set the callback URL, manage stream slots, longpoll for events.
 */
public class Streams internal constructor(
    private val http: AudDHttp,
    private val readPolicy: RetryPolicy,
    private val mutatingPolicy: RetryPolicy,
    private val pollParent: Job? = null,
    private val tokenSupplier: () -> String,
) {
    private val apiBase = AudD.API_BASE

    /** Compute MD5(MD5(api_token)+str(radio_id))[:9] locally — no HTTP call. */
    public fun deriveLongpollCategory(radioId: Long): String =
        io.audd.deriveLongpollCategory(tokenSupplier(), radioId)

    /**
     * Parse a callback POST body (already-parsed JSON) into a typed
     * [CallbackEvent]. See [io.audd.parseCallback] overloads for
     * `ByteArray` / `String` inputs.
     */
    public fun parseCallback(body: JsonObject): CallbackEvent =
        io.audd.parseCallback(body)

    /** Parse a callback POST body from raw bytes. */
    public fun parseCallback(body: ByteArray): CallbackEvent =
        io.audd.parseCallback(body)

    /** Parse a callback POST body from a UTF-8 string. */
    public fun parseCallback(body: String): CallbackEvent =
        io.audd.parseCallback(body)

    private suspend fun postFormReturningResult(
        path: String,
        data: Map<String, String>,
        policy: RetryPolicy,
    ): kotlinx.serialization.json.JsonElement? {
        val resp = retry(policy, shouldRetryByResponse = { shouldRetryResponse(it.httpStatus, policy.retryClass) }) {
            transportAttempt(policy.retryClass) { http.postForm("$apiBase/$path/", data) }
        }
        return decodeSuccess(resp.httpStatus, resp.body, resp.requestId)
    }

    /**
     * @param extraParameters additional form fields the typed params don't cover.
     *   Typed params (`url`) win on collision.
     */
    public suspend fun setCallbackUrl(
        url: String,
        returnMetadata: Any? = null,
        extraParameters: Map<String, String>? = null,
    ) {
        val finalUrl = addReturnToUrl(url, returnMetadata)
        val data = buildMap {
            if (extraParameters != null) putAll(extraParameters)
            put("url", finalUrl)
        }
        postFormReturningResult("setCallbackUrl", data, mutatingPolicy)
    }

    public suspend fun getCallbackUrl(): String {
        val result = postFormReturningResult("getCallbackUrl", emptyMap(), readPolicy)
        return (result as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            ?: result.toString().trim('"')
    }

    /**
     * @param extraParameters additional form fields the typed params don't cover.
     *   Typed params (`url`, `radio_id`, `callbacks`) win on collision.
     */
    public suspend fun add(
        url: String,
        radioId: Long,
        callbacks: String? = null,
        extraParameters: Map<String, String>? = null,
    ) {
        val data = buildMap {
            if (extraParameters != null) putAll(extraParameters)
            put("url", url)
            put("radio_id", radioId.toString())
            if (callbacks != null) put("callbacks", callbacks)
        }
        postFormReturningResult("addStream", data, mutatingPolicy)
    }

    public suspend fun setUrl(radioId: Long, url: String) {
        postFormReturningResult(
            "setStreamUrl",
            mapOf("radio_id" to radioId.toString(), "url" to url),
            mutatingPolicy,
        )
    }

    public suspend fun delete(radioId: Long) {
        postFormReturningResult(
            "deleteStream",
            mapOf("radio_id" to radioId.toString()),
            mutatingPolicy,
        )
    }

    public suspend fun list(): List<Stream> {
        val result = postFormReturningResult("getStreams", emptyMap(), readPolicy)
            ?: return emptyList()
        if (result !is JsonArray) return emptyList()
        // Decode each stream element on its own: drop a non-object / broken
        // element rather than aborting the whole list, and degrade any
        // wrong-typed field within an element to null.
        return result.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            runCatching { decodeObjectLeniently(Stream.serializer(), obj) }.getOrNull()
        }
    }

    /**
     * Start a long-poll subscription. Returns a [LongpollPoll] whose three
     * `Flow`s — [LongpollPoll.matches], [LongpollPoll.notifications],
     * [LongpollPoll.errors] — are fed by a background coroutine.
     *
     * Preflights `getCallbackUrl` unless [LongpollOptions.skipCallbackCheck] is
     * set — this catches the silent-failure mode where no callback URL is
     * configured for the account (longpoll won't deliver anything otherwise).
     *
     * The longpoll request itself carries no api_token: the category
     * authorizes it.
     *
     * Idiomatic usage:
     *
     * ```kotlin
     * audd.streams.longpoll(category).use { poll ->
     *     coroutineScope {
     *         launch { poll.matches.collect { m -> println("${m.song?.artist} — ${m.song?.title}") } }
     *         launch { poll.notifications.collect { n -> println("notif: ${n.notificationMessage}") } }
     *         launch { poll.errors.collect { err -> throw err } }
     *     }
     * }
     * ```
     *
     * See [LongpollPoll] for how the subscription polls, retries and ends.
     * Closing the [AudD] client also ends every subscription it started.
     */
    public suspend fun longpoll(
        category: String,
        opts: LongpollOptions = LongpollOptions(),
    ): LongpollPoll {
        if (!opts.skipCallbackCheck) preflightCallbackUrl()
        return LongpollPoll.start(
            scope = CoroutineScope(SupervisorJob(pollParent) + Dispatchers.Default),
            ownsScope = true,
            source = StreamsLongpollSource(http, readPolicy, "$apiBase/longpoll/"),
            category = category,
            sinceTime = opts.sinceTime,
            timeoutSeconds = opts.timeoutSeconds,
        )
    }

    /**
     * One-step longpoll for callers that have the api_token + radio_id —
     * derives the category locally via [deriveLongpollCategory] and delegates
     * to the category-string overload.
     *
     * ```kotlin
     * audd.streams.longpoll(radioId = 42L).use { poll -> … }
     * ```
     *
     * Tokenless / shared-category subscribers keep using the `category: String`
     * overload — that's the path for browser/mobile/embedded clients that
     * receive a 9-char category from a server-side derive step.
     */
    public suspend fun longpoll(
        radioId: Long,
        opts: LongpollOptions = LongpollOptions(),
    ): LongpollPoll = longpoll(deriveLongpollCategory(radioId), opts)

    /** Shared `getCallbackUrl` preflight. */
    private suspend fun preflightCallbackUrl() {
        try {
            getCallbackUrl()
        } catch (exc: AudDApiException) {
            if (exc.errorCode == NO_CALLBACK_ERROR_CODE) {
                throw AudDInvalidRequestException(
                    errorCode = 0,
                    serverMessage = PREFLIGHT_NO_CALLBACK_HINT,
                    httpStatus = exc.httpStatus,
                    requestId = exc.requestId,
                )
            }
            throw exc
        }
    }

    public companion object {
        internal const val NO_CALLBACK_ERROR_CODE = 19
        public const val PREFLIGHT_NO_CALLBACK_HINT: String =
            "Longpoll won't deliver events because no callback URL is configured for this account. " +
                "Set one first via streams.setCallbackUrl(...) — `https://audd.tech/empty/` is fine if " +
                "you only want longpolling and don't need a real receiver. " +
                "To skip this check, pass LongpollOptions(skipCallbackCheck = true)."
    }
}

/**
 * Knobs for [Streams.longpoll] and [LongpollConsumer.iterate]. Sane defaults:
 * - `sinceTime = null` (start from now)
 * - `timeoutSeconds = 50` (server-side default)
 * - `skipCallbackCheck = false` (preflight is on)
 *
 * @property sinceTime only deliver events newer than this timestamp, in
 *   milliseconds since the epoch.
 * @property timeoutSeconds how long the server holds each request open while
 *   there is nothing new, from 1 to 120 seconds. The HTTP request is given
 *   that long plus a margin before it times out.
 */
public data class LongpollOptions(
    val sinceTime: Long? = null,
    val timeoutSeconds: Int = 50,
    val skipCallbackCheck: Boolean = false,
)

/**
 * An active long-poll subscription. Three typed flows surface its output;
 * collect each one in its own coroutine.
 *
 * `close()` cancels the background producer, including a request or a wait
 * in progress, and lets the flows complete. Idempotent. Implements
 * [AutoCloseable] for `use { }` syntax.
 *
 * ```kotlin
 * audd.streams.longpoll(category).use { poll ->
 *     coroutineScope {
 *         launch { poll.matches.collect { … } }
 *         launch { poll.notifications.collect { … } }
 *         launch { poll.errors.collect { err -> throw err } }
 *     }
 * }
 * ```
 *
 * How the subscription runs:
 * - Each response's events are delivered in order: recognitions on
 *   [matches], stream notifications on [notifications]. An event without a
 *   song, or one the SDK can't read, is skipped.
 * - The next request asks only for events newer than the last one received
 *   (`since_time`), or newer than the server time of a response that had
 *   nothing new.
 * - A response that brings nothing new and no newer server time (an empty or
 *   unreadable body, say) is followed by a wait before the next request:
 *   1 second, doubling up to 30 seconds, and back to none once events or a
 *   newer server time arrive.
 * - Network errors and HTTP 5xx are retried within the client's retry budget
 *   (`maxRetries` on [AudD], `maxAttempts` on [LongpollConsumer]).
 *
 * Errors are terminal: an error the server returns (an invalid `timeout`,
 * say), any other HTTP error, or a transport failure that outlasts the retry
 * budget arrives once on [errors], the producer stops and all three flows
 * complete. After [close] all three complete as well. [matches] buffers
 * without limit and [notifications] keeps the 64 most recent, so a flow you
 * don't collect never holds up the others.
 */
public class LongpollPoll internal constructor(
    public val matches: Flow<StreamCallbackMatch>,
    public val notifications: Flow<StreamCallbackNotification>,
    public val errors: Flow<Throwable>,
    private val scope: CoroutineScope,
    private val ownsScope: Boolean,
) : AutoCloseable {

    @Volatile private var closed: Boolean = false

    /** Cancels the background producer. Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        if (ownsScope) scope.cancel()
    }

    internal companion object {
        internal const val NOTIFICATION_BUFFER = 64

        internal fun start(
            scope: CoroutineScope,
            ownsScope: Boolean,
            source: LongpollSource,
            category: String,
            sinceTime: Long?,
            timeoutSeconds: Int,
        ): LongpollPoll {
            // Each demuxed event lands on exactly one channel. Matches are
            // never dropped; notifications keep the newest few so a consumer
            // that ignores them can't stall the producer.
            val matchesChan = Channel<StreamCallbackMatch>(Channel.UNLIMITED)
            val notifsChan = Channel<StreamCallbackNotification>(
                capacity = NOTIFICATION_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            // Completed with the terminal error, or with null once the
            // producer ends without one (close / cancel).
            val terminal = CompletableDeferred<Throwable?>()

            val producer: Job = scope.launch {
                val error: Throwable = try {
                    runLongpoll(
                        source = source,
                        category = category,
                        initialSinceTime = sinceTime,
                        timeoutSeconds = timeoutSeconds,
                        onMatch = { matchesChan.send(it) },
                        onNotification = { notifsChan.send(it) },
                    )
                } catch (exc: CancellationException) {
                    throw exc
                } catch (exc: Throwable) {
                    exc
                }
                // A request that fails because the poll is being closed is
                // part of the close, not an error to report.
                ensureActive()
                terminal.complete(error)
            }
            // When the producer finishes (error or cancel), close the channels
            // so consumers fall through cleanly.
            producer.invokeOnCompletion {
                matchesChan.close()
                notifsChan.close()
                terminal.complete(null)
            }

            return LongpollPoll(
                matches = matchesChan.receiveAsFlow(),
                notifications = notifsChan.receiveAsFlow(),
                errors = flow { terminal.await()?.let { emit(it) } },
                scope = scope,
                ownsScope = ownsScope,
            )
        }
    }
}

/**
 * Abstracts the HTTP fetch so authenticated and tokenless consumers share the
 * same dispatch loop.
 */
internal interface LongpollSource {
    /** @param minTimeoutMillis how long this request may legitimately stay open. */
    suspend fun fetch(params: Map<String, String>, minTimeoutMillis: Long): HttpResult
}

internal class StreamsLongpollSource(
    private val http: AudDHttp,
    private val policy: RetryPolicy,
    private val url: String,
) : LongpollSource {
    override suspend fun fetch(params: Map<String, String>, minTimeoutMillis: Long): HttpResult =
        retry(policy, shouldRetryByResponse = { shouldRetryResponse(it.httpStatus, RetryClass.READ) }) {
            transportAttempt(RetryClass.READ) { http.get(url, params, minTimeoutMillis) }
        }
}

internal const val LONGPOLL_IDLE_BACKOFF_START_MS = 1_000L
internal const val LONGPOLL_IDLE_BACKOFF_MAX_MS = 30_000L
internal const val LONGPOLL_TIMEOUT_MARGIN_MS = 15_000L

private val longpollLog = LoggerFactory.getLogger(LongpollPoll::class.java)

/**
 * Drive a single longpoll subscription: request, read each response, hand
 * every event to [onMatch] / [onNotification], and work out the next
 * `since_time`.
 *
 * Runs until cancelled, or returns the terminal error (see [LongpollPoll]).
 * [sleep] is the wait between requests that brought nothing new.
 */
internal suspend fun runLongpoll(
    source: LongpollSource,
    category: String,
    initialSinceTime: Long?,
    timeoutSeconds: Int,
    onMatch: suspend (StreamCallbackMatch) -> Unit,
    onNotification: suspend (StreamCallbackNotification) -> Unit,
    sleep: suspend (Long) -> Unit = { delay(it) },
): AudDException {
    var since = initialSinceTime
    var idleDelay = LONGPOLL_IDLE_BACKOFF_START_MS
    while (true) {
        val params = buildMap {
            put("category", category)
            put("timeout", timeoutSeconds.toString())
            since?.let { put("since_time", it.toString()) }
        }
        val resp = try {
            source.fetch(params, timeoutSeconds * 1_000L + LONGPOLL_TIMEOUT_MARGIN_MS)
        } catch (exc: CancellationException) {
            throw exc
        } catch (exc: Throwable) {
            return asAudDException(exc)
        }
        if (resp.httpStatus >= HTTP_CLIENT_ERROR_FLOOR) {
            return AudDServerException(
                errorCode = 0,
                serverMessage = "Longpoll endpoint returned HTTP ${resp.httpStatus}",
                httpStatus = resp.httpStatus,
                requestId = resp.requestId,
                rawResponse = null,
            )
        }
        val delivery = when (val body = readLongpollBody(resp.body, resp.httpStatus, resp.requestId)) {
            is LongpollBody.Failed -> return body.error
            is LongpollBody.Delivery -> body
        }
        for (event in delivery.events) {
            when (event) {
                is CallbackEvent.Match -> onMatch(event.match)
                is CallbackEvent.Notification -> onNotification(event.notification)
            }
        }
        val next = delivery.nextSince
        val advanced = next != null && next != since
        if (next != null) since = next
        if (advanced || delivery.carriedEvent) {
            idleDelay = LONGPOLL_IDLE_BACKOFF_START_MS
        } else {
            if (delivery.unrecognized) {
                longpollLog.warn(
                    "Unexpected longpoll response (HTTP {}, {} chars); polling again in {} ms",
                    resp.httpStatus,
                    resp.body.length,
                    idleDelay,
                )
            }
            sleep(idleDelay)
            idleDelay = minOf(idleDelay * 2, LONGPOLL_IDLE_BACKOFF_MAX_MS)
        }
    }
}

/** What one longpoll response body means for the loop. */
internal sealed class LongpollBody {
    /** The server returned an error: surface it and stop. */
    class Failed(val error: AudDException) : LongpollBody()

    /**
     * Events to deliver (possibly none) and the `since_time` the response
     * moves to (null = none).
     *
     * @property carriedEvent a body with `result` / `notification` at the top
     *   level; the next request follows right away.
     * @property unrecognized the body wasn't any shape longpoll returns.
     */
    class Delivery(
        val events: List<CallbackEvent>,
        val nextSince: Long?,
        val carriedEvent: Boolean = false,
        val unrecognized: Boolean = false,
    ) : LongpollBody()
}

/**
 * Interpret one longpoll response body. Never throws: anything unreadable is
 * an empty, unrecognized [LongpollBody.Delivery].
 *
 * Shapes:
 * - `{"events":[{"timestamp":…,"data":{…callback body…}}, …]}`
 * - `{"timeout":"no events before timeout","timestamp":…}`
 * - `{"error":"…"}` (or a `status: error` body)
 * - a callback body (`result` / `notification`) at the top level, with an
 *   optional top-level `timestamp`
 */
internal fun readLongpollBody(text: String, httpStatus: Int, requestId: String?): LongpollBody {
    val obj = try {
        auddJson.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: return LongpollBody.Delivery(emptyList(), nextSince = null, unrecognized = true)

    longpollError(obj, httpStatus, requestId)?.let { return LongpollBody.Failed(it) }

    val events = obj["events"]
    if (events is JsonArray) return readLongpollEvents(events)

    if (obj["result"] is JsonObject || obj["notification"] is JsonObject) {
        val parsed = runCatching { parseCallback(obj) }.getOrNull()
        return LongpollBody.Delivery(
            events = listOfNotNull(parsed?.takeIf { it.isDeliverable() }),
            nextSince = lenientLong(obj["timestamp"]),
            carriedEvent = true,
        )
    }

    val timestamp = lenientLong(obj["timestamp"])
    val known = timestamp != null || "timeout" in obj || "events" in obj
    return LongpollBody.Delivery(emptyList(), nextSince = timestamp, unrecognized = !known)
}

private fun readLongpollEvents(events: JsonArray): LongpollBody.Delivery {
    var maxTimestamp: Long? = null
    val out = ArrayList<CallbackEvent>(events.size)
    for (element in events) {
        val event = element as? JsonObject ?: continue
        lenientLong(event["timestamp"])?.let { ts ->
            val current = maxTimestamp
            if (current == null || ts > current) maxTimestamp = ts
        }
        val data = eventData(event["data"]) ?: continue
        val parsed = runCatching { parseCallback(data) }.getOrNull() ?: continue
        if (parsed.isDeliverable()) out += parsed
    }
    return LongpollBody.Delivery(out, nextSince = maxTimestamp)
}

/** An event's `data` is a callback body, sent as an object or as a JSON string. */
private fun eventData(element: JsonElement?): JsonObject? = when (element) {
    is JsonObject -> element
    is JsonPrimitive -> if (element.isString) {
        runCatching { auddJson.parseToJsonElement(element.content) as? JsonObject }.getOrNull()
    } else {
        null
    }
    else -> null
}

/** A match with no song (empty `results`) carries nothing to deliver. */
private fun CallbackEvent.isDeliverable(): Boolean =
    this !is CallbackEvent.Match || match.song != null

private fun longpollError(obj: JsonObject, httpStatus: Int, requestId: String?): AudDException? {
    val error = obj["error"]?.takeUnless { it is JsonNull }
    val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
    if (error == null && status != "error") return null
    if (error is JsonPrimitive) {
        return AudDInvalidRequestException(
            errorCode = 0,
            serverMessage = error.content,
            httpStatus = httpStatus,
            requestId = requestId,
            rawResponse = obj,
        )
    }
    return runCatching { errorFromResponse(obj, httpStatus, requestId) }.getOrElse {
        AudDServerException(
            errorCode = 0,
            serverMessage = "Longpoll error: ${error ?: obj}",
            httpStatus = httpStatus,
            requestId = requestId,
            rawResponse = obj,
        )
    }
}

/** A millisecond timestamp sent as a number or a numeric string; anything else is null. */
internal fun lenientLong(element: JsonElement?): Long? {
    val prim = element as? JsonPrimitive ?: return null
    if (prim is JsonNull) return null
    val text = prim.content.trim()
    text.toLongOrNull()?.let { return it }
    if (text.any { it.isLetter() && it != 'e' && it != 'E' }) return null
    val value = text.toDoubleOrNull() ?: return null
    return if (value.isFinite()) value.toLong() else null
}
