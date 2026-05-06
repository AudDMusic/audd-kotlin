package io.audd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Streams namespace — set the callback URL, manage stream slots, longpoll for events.
 */
public class Streams internal constructor(
    private val http: AudDHttp,
    private val readPolicy: RetryPolicy,
    private val mutatingPolicy: RetryPolicy,
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
            try {
                http.postForm("$apiBase/$path/", data)
            } catch (exc: Throwable) {
                if (shouldRetryException(exc, policy.retryClass)) throw exc
                throw AudDConnectionException(exc.message ?: exc.toString(), original = exc)
            }
        }
        return decodeSuccess(resp.httpStatus, resp.body, resp.requestId)
    }

    public suspend fun setCallbackUrl(url: String, returnMetadata: Any? = null) {
        val finalUrl = addReturnToUrl(url, returnMetadata)
        postFormReturningResult("setCallbackUrl", mapOf("url" to finalUrl), mutatingPolicy)
    }

    public suspend fun getCallbackUrl(): String {
        val result = postFormReturningResult("getCallbackUrl", emptyMap(), readPolicy)
        return (result as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            ?: result.toString().trim('"')
    }

    public suspend fun add(url: String, radioId: Long, callbacks: String? = null) {
        val data = buildMap {
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
        return result.map { auddJson.decodeFromJsonElement(Stream.serializer(), it) }
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
     * Idiomatic usage:
     *
     * ```kotlin
     * audd.streams.longpoll(category).use { poll ->
     *     coroutineScope {
     *         launch { poll.matches.collect { m -> println("${m.song.artist} — ${m.song.title}") } }
     *         launch { poll.notifications.collect { n -> println("notif: ${n.notificationMessage}") } }
     *         launch { poll.errors.collect { err -> throw err } }
     *     }
     * }
     * ```
     *
     * The producer terminates after the first error fires; the [LongpollPoll]
     * `close()` (or `use { }`) cancels the producer scope and lets all three
     * flows complete.
     */
    public suspend fun longpoll(
        category: String,
        opts: LongpollOptions = LongpollOptions(),
    ): LongpollPoll {
        if (!opts.skipCallbackCheck) preflightCallbackUrl()
        return LongpollPoll.start(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ownsScope = true,
            source = StreamsLongpollSource(http, readPolicy, "$apiBase/longpoll/"),
            category = category,
            sinceTime = opts.sinceTime,
            timeoutSeconds = opts.timeoutSeconds,
        )
    }

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
 * Knobs for [Streams.longpoll]. Sane defaults:
 * - `sinceTime = null` (start from now)
 * - `timeoutSeconds = 50` (server-side default)
 * - `skipCallbackCheck = false` (preflight is on)
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
 * `close()` cancels the background producer scope and lets the flows complete.
 * Idempotent. Implements [AutoCloseable] for `use { }` syntax.
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
 * Errors are terminal: when one fires on [errors], the producer stops and all
 * three flows complete. Subsequent collection on any flow returns immediately.
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
        internal fun start(
            scope: CoroutineScope,
            ownsScope: Boolean,
            source: LongpollSource,
            category: String,
            sinceTime: Long?,
            timeoutSeconds: Int,
        ): LongpollPoll {
            // SharedFlow w/ replay=0 + Channel-backed transports: each demuxed
            // event lands on exactly one flow; consumers attach via the public
            // Flow<T> handles below. Buffer 64 so the producer doesn't block on
            // a slow consumer for short bursts.
            val matchesChan = Channel<StreamCallbackMatch>(capacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
            val notifsChan = Channel<StreamCallbackNotification>(capacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
            val errorsFlow = MutableSharedFlow<Throwable>(replay = 1, extraBufferCapacity = 1)

            val producer: Job = scope.launch {
                runLongpoll(
                    source = source,
                    category = category,
                    initialSinceTime = sinceTime,
                    timeoutSeconds = timeoutSeconds,
                    matches = matchesChan,
                    notifs = notifsChan,
                    errors = errorsFlow,
                )
            }
            // When the producer finishes (error or cancel), close the channels
            // so consumers fall through cleanly.
            producer.invokeOnCompletion {
                matchesChan.close()
                notifsChan.close()
            }

            return LongpollPoll(
                matches = matchesChan.consumeAsFlow(),
                notifications = notifsChan.consumeAsFlow(),
                errors = errorsFlow.asSharedFlow(),
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
    suspend fun fetch(params: Map<String, String>): HttpResult
}

internal class StreamsLongpollSource(
    private val http: AudDHttp,
    private val policy: RetryPolicy,
    private val url: String,
) : LongpollSource {
    override suspend fun fetch(params: Map<String, String>): HttpResult =
        retry(policy, shouldRetryByResponse = { shouldRetryResponse(it.httpStatus, RetryClass.READ) }) {
            try {
                http.get(url, params)
            } catch (exc: Throwable) {
                if (shouldRetryException(exc, RetryClass.READ)) throw exc
                throw AudDConnectionException(exc.message ?: exc.toString(), original = exc)
            }
        }
}

/**
 * Drive a single longpoll subscription: read HTTP responses, parse each into
 * a [StreamCallbackMatch] / [StreamCallbackNotification], demux onto channels.
 *
 * Stops on first error (after sending it on [errors]). Server-side timeouts
 * (envelopes with no `result` / `notification`) are benign — loop continues.
 */
internal suspend fun runLongpoll(
    source: LongpollSource,
    category: String,
    initialSinceTime: Long?,
    timeoutSeconds: Int,
    matches: SendChannel<StreamCallbackMatch>,
    notifs: SendChannel<StreamCallbackNotification>,
    errors: MutableSharedFlow<Throwable>,
) {
    var curSince = initialSinceTime
    while (true) {
        val params = buildMap {
            put("category", category)
            put("timeout", timeoutSeconds.toString())
            if (curSince != null) put("since_time", curSince.toString())
        }
        val resp = try {
            source.fetch(params)
        } catch (exc: AudDException) {
            errors.emit(exc)
            return
        } catch (exc: Throwable) {
            errors.emit(AudDConnectionException(exc.message ?: exc.toString(), original = exc))
            return
        }
        if (resp.httpStatus >= HTTP_CLIENT_ERROR_FLOOR) {
            errors.emit(
                AudDServerException(
                    errorCode = 0,
                    serverMessage = "Longpoll endpoint returned HTTP ${resp.httpStatus}",
                    httpStatus = resp.httpStatus,
                    requestId = resp.requestId,
                    rawResponse = null,
                ),
            )
            return
        }
        val body: JsonObject = try {
            auddJson.parseToJsonElement(resp.body) as? JsonObject
                ?: throw AudDSerializationException(
                    "Longpoll response was not a JSON object",
                    rawText = resp.body,
                )
        } catch (exc: AudDException) {
            errors.emit(exc)
            return
        } catch (exc: Throwable) {
            errors.emit(
                AudDSerializationException(
                    "Failed to parse longpoll response: ${exc.message}",
                    rawText = resp.body,
                    cause = exc,
                ),
            )
            return
        }
        // Advance since_time first, regardless of whether we have a match /
        // notification (server-side timeouts also bump it).
        body["timestamp"]?.jsonPrimitive?.longOrNull?.let { curSince = it }

        val hasMatch = body["result"] is JsonObject
        val hasNotif = body["notification"] is JsonObject
        if (!hasMatch && !hasNotif) {
            // Server-side timeout (no events before timeout) — keep polling.
            continue
        }
        try {
            when (val parsed = parseCallback(body)) {
                is CallbackEvent.Match -> matches.send(parsed.match)
                is CallbackEvent.Notification -> notifs.send(parsed.notification)
            }
        } catch (exc: AudDException) {
            errors.emit(exc)
            return
        }
    }
}
