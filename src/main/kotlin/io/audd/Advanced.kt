package io.audd

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Advanced namespace — lyrics search and a raw escape hatch for endpoints
 * the SDK doesn't yet wrap with typed methods.
 *
 * Advanced uses the [RetryClass.RECOGNITION] policy: `findLyrics` is metered
 * and shouldn't double-bill on a post-upload read timeout.
 */
public class Advanced internal constructor(
    private val http: AudDHttp,
    private val recognitionPolicy: RetryPolicy,
) {
    private val apiBase = AudD.API_BASE

    /** Search lyrics by free-text query. */
    public suspend fun findLyrics(query: String): List<LyricsResult> {
        val body = rawRequest("findLyrics", mapOf("q" to query))
        if (body["status"]?.jsonPrimitive?.contentOrNull == "error") {
            raiseFromErrorResponse(body, httpStatus = 200, requestId = null)
        }
        val result = body["result"] as? JsonArray ?: return emptyList()
        // Decode each lyrics entry independently: drop a non-object / broken
        // element rather than aborting the whole list, and degrade any
        // wrong-typed field within an entry to null.
        return result.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            runCatching { decodeObjectLeniently(LyricsResult.serializer(), obj) }.getOrNull()
        }
    }

    /**
     * Hit any AudD endpoint by method name and return the raw JSON body.
     *
     * Useful for endpoints not yet wrapped by typed methods on this SDK.
     */
    public suspend fun rawRequest(method: String, params: Map<String, String> = emptyMap()): JsonObject {
        val resp = retry(recognitionPolicy, shouldRetryByResponse = { shouldRetryResponse(it.httpStatus, RetryClass.RECOGNITION) }) {
            try {
                http.postForm("$apiBase/$method/", params)
            } catch (exc: Throwable) {
                if (shouldRetryException(exc, RetryClass.RECOGNITION)) throw exc
                throw AudDConnectionException(exc.message ?: exc.toString(), original = exc)
            }
        }
        val parsed = runCatching { auddJson.parseToJsonElement(resp.body) as? JsonObject }
            .getOrNull()
            ?: throw AudDSerializationException("Unparseable response", rawText = resp.body)
        return parsed
    }
}
