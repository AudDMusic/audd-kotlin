package io.audd

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import java.util.concurrent.atomic.AtomicReference

/** A single HTTP exchange wrapped for the SDK's response-decoder. */
internal data class HttpResult(
    val httpStatus: Int,
    val body: String,
    val requestId: String?,
)

internal data class Timeouts(
    val connectMillis: Long = 30_000L,
    val readMillis: Long = 60_000L,
    val writeMillis: Long = 60_000L,
)

internal val ENTERPRISE_TIMEOUTS = Timeouts(connectMillis = 30_000L, readMillis = 3_600_000L, writeMillis = 3_600_000L)

internal class AudDHttp(
    apiToken: String,
    private val timeouts: Timeouts = Timeouts(),
    engine: HttpClientEngine? = null,
    httpClient: HttpClient? = null,
) : AutoCloseable {

    /**
     * Token cell — `AtomicReference` so [setApiToken] is safe to call from any
     * thread concurrently with in-flight requests. In-flight requests carry
     * the token they read at request-build time; new requests pick up the
     * rotated value.
     */
    private val tokenRef: AtomicReference<String> = AtomicReference(apiToken)

    private val ownsClient: Boolean
    private val client: HttpClient

    init {
        when {
            httpClient != null -> {
                client = httpClient
                ownsClient = false
            }
            engine != null -> {
                client = HttpClient(engine) { configure() }
                ownsClient = true
            }
            else -> {
                client = HttpClient(CIO) { configure() }
                ownsClient = true
            }
        }
    }

    private fun io.ktor.client.HttpClientConfig<*>.configure() {
        install(HttpTimeout) {
            connectTimeoutMillis = timeouts.connectMillis
            requestTimeoutMillis = timeouts.readMillis
            socketTimeoutMillis = timeouts.readMillis
        }
        expectSuccess = false
    }

    /** Replace the api_token used for subsequent requests. Empty values rejected. */
    fun setApiToken(newToken: String) {
        require(newToken.isNotBlank()) { "newToken must be non-empty" }
        tokenRef.set(newToken)
    }

    suspend fun postForm(url: String, data: Map<String, String>, file: FilePart? = null): HttpResult {
        val full = data.toMutableMap()
        full["api_token"] = tokenRef.get()
        val response = client.post(url) {
            headers { append(HttpHeaders.UserAgent, userAgent()) }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        full.forEach { (k, v) -> append(k, v) }
                        if (file != null) {
                            append(
                                key = "file",
                                value = file.bytes,
                                headers = io.ktor.http.Headers.build {
                                    append(HttpHeaders.ContentDisposition, "filename=\"${file.filename}\"")
                                    append(HttpHeaders.ContentType, file.contentType)
                                },
                            )
                        }
                    },
                ),
            )
        }
        return wrap(response)
    }

    suspend fun get(url: String, params: Map<String, String>): HttpResult {
        val full = params.toMutableMap()
        full.putIfAbsent("api_token", tokenRef.get())
        val response = client.get(url) {
            headers { append(HttpHeaders.UserAgent, userAgent()) }
            full.forEach { (k, v) -> parameter(k, v) }
        }
        return wrap(response)
    }

    private suspend fun wrap(response: HttpResponse): HttpResult = HttpResult(
        httpStatus = response.status.value,
        body = response.bodyAsText(),
        requestId = requestId(response.headers),
    )

    override fun close() {
        if (ownsClient) client.close()
    }
}

internal fun requestId(headers: Headers): String? =
    headers["x-request-id"] ?: headers["X-Request-ID"]
