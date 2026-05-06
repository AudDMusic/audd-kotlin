package io.audd

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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

    /** Parse a callback POST body into a typed payload. */
    public fun parseCallback(body: JsonObject): StreamCallbackPayload =
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
     * Yield successive longpoll envelopes for [category] as raw [JsonObject]s.
     *
     * Preflights `getCallbackUrl` unless [skipCallbackCheck] is true. If no
     * callback URL is configured, raises [AudDInvalidRequestException] with a
     * hint — longpoll won't deliver anything otherwise.
     *
     * Most callers should prefer [longpollFlow], which yields typed
     * [LongpollEvent]s and propagates non-2xx HTTP errors as
     * [AudDServerException] (consistent with [LongpollConsumer]).
     */
    public fun longpoll(
        category: String,
        sinceTime: Long? = null,
        timeoutSeconds: Int = 50,
        skipCallbackCheck: Boolean = false,
    ): Flow<JsonObject> = flow {
        if (!skipCallbackCheck) preflightCallbackUrl()
        var curSince = sinceTime
        while (true) {
            val params = buildMap {
                put("category", category)
                put("timeout", timeoutSeconds.toString())
                if (curSince != null) put("since_time", curSince.toString())
            }
            val resp = retry(readPolicy, shouldRetryByResponse = { shouldRetryResponse(it.httpStatus, RetryClass.READ) }) {
                try {
                    http.get("$apiBase/longpoll/", params)
                } catch (exc: Throwable) {
                    if (shouldRetryException(exc, RetryClass.READ)) throw exc
                    throw AudDConnectionException(exc.message ?: exc.toString(), original = exc)
                }
            }
            val body = runCatching {
                auddJson.parseToJsonElement(resp.body) as? JsonObject
            }.getOrNull() ?: throw AudDSerializationException("Unparseable longpoll response", rawText = resp.body)
            emit(body)
            val ts = body["timestamp"]?.jsonPrimitive?.longOrNull
            if (ts != null) curSince = ts
        }
    }

    /**
     * Yield successive longpoll envelopes for [category] as typed
     * [LongpollEvent]s. The recommended longpoll API for Kotlin coroutine
     * consumers:
     *
     * ```kotlin
     * audd.streams.longpollFlow(category).collect { event -> … }
     * audd.streams.longpollFlow(category).take(5).toList()
     * ```
     *
     * Preflights `getCallbackUrl` unless [skipCallbackCheck] is true. Non-2xx
     * HTTP responses raise [AudDServerException]; non-JSON payloads raise
     * [AudDSerializationException]; both are consistent with
     * [LongpollConsumer.events].
     *
     * `since_time` is advanced from each envelope's `timestamp` so the next
     * iteration only receives strictly-newer events.
     */
    public fun longpollFlow(
        category: String,
        sinceTime: Long? = null,
        timeoutSeconds: Int = 50,
        skipCallbackCheck: Boolean = false,
    ): Flow<LongpollEvent> = flow {
        if (!skipCallbackCheck) preflightCallbackUrl()
        longpollEventFlow(
            http = http,
            policy = readPolicy,
            url = "$apiBase/longpoll/",
            category = category,
            sinceTime = sinceTime,
            timeoutSeconds = timeoutSeconds,
        ).collect { emit(it) }
    }

    /** Shared `getCallbackUrl` preflight used by [longpoll] and [longpollFlow]. */
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
                "To skip this check, pass skipCallbackCheck = true."
    }
}
