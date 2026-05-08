package io.audd

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Top-level client for the AudD music recognition API.
 *
 * All public methods are `suspend` — call from `runBlocking { }` or any
 * coroutine scope. The client owns two underlying transports (a default one
 * and an enterprise one with longer timeouts); both are closed via [close].
 *
 * Implements [AutoCloseable] so it works with Kotlin's `use`:
 *
 * ```kotlin
 * AudD("test").use { audd ->
 *   val result = audd.recognize(Source.Url("https://audd.tech/example.mp3"))
 *   println("${result?.artist} — ${result?.title}")
 * }
 * ```
 *
 * The constructor accepts `apiToken = null` (or empty), in which case the SDK
 * reads `AUDD_API_TOKEN` from the environment. If neither is set, an
 * [IllegalArgumentException] is thrown with a hint to https://dashboard.audd.io.
 * Use [AudD.fromEnvironment] for an environment-only construction with the
 * same fallback semantics.
 *
 * Token rotation: call [setApiToken] from any thread to swap the token used
 * by subsequent requests. In-flight requests continue with the previous
 * value (no abort).
 *
 * Observability: pass [onEvent] to receive [AudDEvent]s for every request,
 * response, and exception. Hook exceptions are swallowed via `runCatching`
 * so they never break the request path. Events never carry the api_token or
 * body bytes.
 *
 * @property maxRetries per-request retry budget for cost-aware retries.
 * @property backoffFactor base for exponential backoff in seconds.
 * @property onDeprecation hook for code-51 deprecation warnings — defaults to
 *   logging via SLF4J (`io.audd.AudD`). Override to capture or silence.
 */
public class AudD(
    apiToken: String?,
    public val maxRetries: Int = 3,
    public val backoffFactor: Double = 0.5,
    engine: HttpClientEngine? = null,
    httpClient: HttpClient? = null,
    public val onDeprecation: DeprecationCallback = defaultOnDeprecation,
    public val onEvent: ((AudDEvent) -> Unit)? = null,
) : AutoCloseable {

    /**
     * Token cell — `AtomicReference` so [setApiToken] is thread-safe vs
     * concurrent requests, and the value visible to a request is exactly the
     * one read at that moment in [apiToken].
     */
    private val tokenRef: AtomicReference<String> = AtomicReference(resolveToken(apiToken))

    /** The currently-configured API token. Reads via [AtomicReference]. */
    public val apiToken: String get() = tokenRef.get()

    private val http: AudDHttp = AudDHttp(tokenRef.get(), engine = engine, httpClient = httpClient)
    private val enterpriseHttp: AudDHttp = AudDHttp(
        tokenRef.get(),
        timeouts = ENTERPRISE_TIMEOUTS,
        engine = engine,
        httpClient = httpClient,
    )

    private val readPolicy: RetryPolicy get() = RetryPolicy(RetryClass.READ, maxRetries, backoffFactor)
    private val recognitionPolicy: RetryPolicy get() = RetryPolicy(RetryClass.RECOGNITION, maxRetries, backoffFactor)
    private val mutatingPolicy: RetryPolicy get() = RetryPolicy(RetryClass.MUTATING, maxRetries, backoffFactor)

    /** Streams namespace — set callback URL, manage stream slots, longpoll for events. */
    public val streams: Streams by lazy { Streams(http, readPolicy, mutatingPolicy) { tokenRef.get() } }

    /**
     * Custom-catalog namespace.
     *
     * **Custom-catalog upload is NOT music recognition.** It adds songs to your
     * private fingerprint database. Use [recognize] / [recognizeEnterprise] for
     * recognition. See [CustomCatalog].
     */
    public val customCatalog: CustomCatalog by lazy { CustomCatalog(http, mutatingPolicy) }

    /** Advanced namespace — lyrics search and a raw escape hatch. */
    public val advanced: Advanced by lazy { Advanced(http, recognitionPolicy) }

    /**
     * Replace the api_token used for subsequent requests.
     *
     * Thread-safe: in-flight requests continue with their captured token; new
     * requests use the rotated value. Empty / blank input throws
     * [IllegalArgumentException].
     */
    public fun setApiToken(newToken: String) {
        require(newToken.isNotBlank()) { "newToken must be a non-empty string" }
        tokenRef.set(newToken)
        http.setApiToken(newToken)
        enterpriseHttp.setApiToken(newToken)
    }

    /**
     * Recognize a song from a URL, file, byte buffer, or stream.
     *
     * Returns null when the recognition succeeded but no song matched.
     *
     * @param source one of [Source.Url], [Source.FilePath], [Source.Bytes], [Source.Stream].
     * @param returnExtras additional metadata providers — `"apple_music"`, `"spotify"`,
     *   `"deezer"`, `"napster"`, `"musicbrainz"`, `"lyrics"`, `"timecode"`, or
     *   a comma-separated combination.
     * @param market two-letter market hint (e.g. `"us"`).
     */
    public suspend fun recognize(
        source: Source,
        returnExtras: Any? = null,
        market: String? = null,
    ): RecognitionResult? {
        val reopen = prepareSource(source)
        val ret = formatReturn(returnExtras)
        val url = "$API_BASE/"
        emitEvent(AudDEvent(AudDEvent.Kind.REQUEST, "recognize", url))
        val started = System.nanoTime()
        val resp = try {
            retry(recognitionPolicy, shouldRetryByResponse = { shouldRetryResponse(it.httpStatus, RetryClass.RECOGNITION) }) {
                val part = reopen()
                val data = part.data.toMutableMap().apply {
                    if (ret != null) put("return", ret)
                    if (market != null) put("market", market)
                }
                try {
                    http.postForm(url, data, part.file)
                } catch (exc: Throwable) {
                    if (shouldRetryException(exc, RetryClass.RECOGNITION)) throw exc
                    throw AudDConnectionException(exc.message ?: exc.toString(), original = exc)
                }
            }
        } catch (exc: Throwable) {
            emitEvent(
                AudDEvent(
                    kind = AudDEvent.Kind.EXCEPTION,
                    method = "recognize",
                    url = url,
                    elapsedMs = elapsedMillis(started),
                    extras = mapOf("error_type" to (exc::class.simpleName ?: "Throwable")),
                ),
            )
            throw exc
        }
        emitEvent(
            AudDEvent(
                kind = AudDEvent.Kind.RESPONSE,
                method = "recognize",
                url = url,
                requestId = resp.requestId,
                httpStatus = resp.httpStatus,
                elapsedMs = elapsedMillis(started),
            ),
        )
        return decodeRecognize(resp.httpStatus, resp.body, resp.requestId, onDeprecation)
    }

    /** Convenience overload: pass a URL string directly. */
    public suspend fun recognize(
        url: String,
        returnExtras: Any? = null,
        market: String? = null,
    ): RecognitionResult? = recognize(Source.Url(url), returnExtras, market)

    /**
     * Enterprise recognition — for files longer than 25 seconds; returns a flat
     * list of [EnterpriseMatch]es spanning every chunk in the upload.
     */
    public suspend fun recognizeEnterprise(
        source: Source,
        returnExtras: Any? = null,
        skip: Int? = null,
        every: Int? = null,
        limit: Int? = null,
        skipFirstSeconds: Int? = null,
        useTimecode: Boolean? = null,
        accurateOffsets: Boolean? = null,
    ): List<EnterpriseMatch> {
        val reopen = prepareSource(source)
        val extra = buildEnterpriseFields(
            formatReturn(returnExtras), skip, every, limit, skipFirstSeconds, useTimecode, accurateOffsets,
        )
        val url = "$ENTERPRISE_BASE/"
        emitEvent(AudDEvent(AudDEvent.Kind.REQUEST, "recognize", url))
        val started = System.nanoTime()
        val resp = try {
            retry(recognitionPolicy, shouldRetryByResponse = { shouldRetryResponse(it.httpStatus, RetryClass.RECOGNITION) }) {
                val part = reopen()
                val data = part.data.toMutableMap().apply { putAll(extra) }
                try {
                    enterpriseHttp.postForm(url, data, part.file)
                } catch (exc: Throwable) {
                    if (shouldRetryException(exc, RetryClass.RECOGNITION)) throw exc
                    throw AudDConnectionException(exc.message ?: exc.toString(), original = exc)
                }
            }
        } catch (exc: Throwable) {
            emitEvent(
                AudDEvent(
                    kind = AudDEvent.Kind.EXCEPTION,
                    method = "recognize",
                    url = url,
                    elapsedMs = elapsedMillis(started),
                    extras = mapOf("error_type" to (exc::class.simpleName ?: "Throwable")),
                ),
            )
            throw exc
        }
        emitEvent(
            AudDEvent(
                kind = AudDEvent.Kind.RESPONSE,
                method = "recognize",
                url = url,
                requestId = resp.requestId,
                httpStatus = resp.httpStatus,
                elapsedMs = elapsedMillis(started),
            ),
        )
        return decodeEnterprise(resp.httpStatus, resp.body, resp.requestId, onDeprecation)
    }

    override fun close() {
        http.close()
        enterpriseHttp.close()
    }

    /** Wrap the configured [onEvent] hook so a thrown exception cannot break the request path. */
    private fun emitEvent(event: AudDEvent) {
        val hook = onEvent ?: return
        runCatching { hook(event) }
    }

    public companion object {
        public const val API_BASE: String = "https://api.audd.io"
        public const val ENTERPRISE_BASE: String = "https://enterprise.audd.io"

        /** Environment variable consulted when `apiToken` is null/empty. */
        public const val TOKEN_ENV_VAR: String = "AUDD_API_TOKEN"

        private val log = LoggerFactory.getLogger(AudD::class.java)
        public val defaultOnDeprecation: DeprecationCallback = { msg ->
            log.warn("AudD API deprecation: {}", msg)
        }

        /**
         * Construct an [AudD] reading the token from `AUDD_API_TOKEN`.
         *
         * Equivalent to `AudD(apiToken = null, ...)` — included as a discoverable
         * factory for environment-only configurations. Throws
         * [IllegalArgumentException] if the env var is unset/empty.
         */
        @JvmStatic
        @JvmOverloads
        public fun fromEnvironment(
            maxRetries: Int = 3,
            backoffFactor: Double = 0.5,
            engine: HttpClientEngine? = null,
            httpClient: HttpClient? = null,
            onDeprecation: DeprecationCallback = defaultOnDeprecation,
            onEvent: ((AudDEvent) -> Unit)? = null,
        ): AudD = AudD(
            apiToken = null,
            maxRetries = maxRetries,
            backoffFactor = backoffFactor,
            engine = engine,
            httpClient = httpClient,
            onDeprecation = onDeprecation,
            onEvent = onEvent,
        )
    }
}

/**
 * Lifecycle event emitted to the [AudD.onEvent] hook around every HTTP
 * exchange.
 *
 * Plain data, immutable. **Never** carries the api_token or response body
 * bytes — observability must not become an exfiltration vector.
 *
 * @property kind which lifecycle phase produced this event.
 * @property method AudD method name (e.g. `"recognize"`, `"addStream"`).
 * @property url full request URL.
 * @property requestId server-assigned request id (from the `x-request-id`
 *   header), present on RESPONSE.
 * @property httpStatus HTTP status, present on RESPONSE.
 * @property elapsedMs wall-clock milliseconds between request emission and
 *   the terminating event (RESPONSE or EXCEPTION).
 * @property errorCode AudD error code when known (reserved for future use).
 * @property extras free-form non-secret key/value bag — error type names,
 *   retry counts, etc.
 */
public data class AudDEvent(
    val kind: Kind,
    val method: String,
    val url: String,
    val requestId: String? = null,
    val httpStatus: Int? = null,
    val elapsedMs: Long? = null,
    val errorCode: Int? = null,
    val extras: Map<String, Any?> = emptyMap(),
) {
    public enum class Kind { REQUEST, RESPONSE, EXCEPTION }
}

private fun elapsedMillis(startedNanos: Long): Long =
    (System.nanoTime() - startedNanos) / 1_000_000L

/**
 * Resolve the api_token: explicit arg → `AUDD_API_TOKEN` env var → error.
 *
 * Raising loudly here is deliberate — silently allowing an empty token would
 * otherwise surface as a confusing #901 from the server later.
 */
internal fun resolveToken(apiToken: String?): String {
    if (!apiToken.isNullOrBlank()) return apiToken
    val env = System.getenv(AudD.TOKEN_ENV_VAR)
    if (!env.isNullOrBlank()) return env
    throw IllegalArgumentException(
        "AudD api_token not supplied and ${AudD.TOKEN_ENV_VAR} env var is unset. " +
            "Get a token at https://dashboard.audd.io and pass it as AudD(apiToken = ...) " +
            "or set ${AudD.TOKEN_ENV_VAR}.",
    )
}

internal fun formatReturn(value: Any?): String? = when (value) {
    null -> null
    is String -> value
    is Iterable<*> -> value.joinToString(",") { it.toString() }
    is Array<*> -> value.joinToString(",") { it.toString() }
    else -> value.toString()
}

internal fun buildEnterpriseFields(
    returnStr: String?,
    skip: Int?,
    every: Int?,
    limit: Int?,
    skipFirstSeconds: Int?,
    useTimecode: Boolean?,
    accurateOffsets: Boolean?,
): Map<String, String> = buildMap {
    if (returnStr != null) put("return", returnStr)
    if (skip != null) put("skip", skip.toString())
    if (every != null) put("every", every.toString())
    if (limit != null) put("limit", limit.toString())
    if (skipFirstSeconds != null) put("skip_first_seconds", skipFirstSeconds.toString())
    if (useTimecode != null) put("use_timecode", if (useTimecode) "true" else "false")
    if (accurateOffsets != null) put("accurate_offsets", if (accurateOffsets) "true" else "false")
}
