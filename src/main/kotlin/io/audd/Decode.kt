package io.audd

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val DEPRECATED_PARAMS_CODE = 51
internal const val HTTP_CLIENT_ERROR_FLOOR = 400

/** Hook so a caller can capture deprecation warnings (default: SLF4J logger.warn). */
public typealias DeprecationCallback = (String) -> Unit

/**
 * Inspect a response, raise typed errors for obvious failures, else return the body.
 *
 * Distinguishes:
 * - non-2xx HTTP with non-JSON body → AudDServerException (preserves status)
 * - 2xx with non-JSON body → AudDSerializationException
 * - status=error with code-51 + result → emit deprecation warning, strip error, fall through
 * - status=error otherwise → throw typed exception
 * - status=success or now-error-stripped → return body
 */
internal fun decodeOrThrow(
    httpStatus: Int,
    rawBody: String,
    requestId: String?,
    onDeprecation: DeprecationCallback,
): JsonObject {
    val body: JsonObject? = try {
        val element = auddJson.parseToJsonElement(rawBody)
        element as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    if (body == null) {
        if (httpStatus >= HTTP_CLIENT_ERROR_FLOOR) {
            throw AudDServerException(
                errorCode = 0,
                serverMessage = "HTTP $httpStatus with non-JSON response body",
                httpStatus = httpStatus,
                requestId = requestId,
                rawResponse = null,
            )
        }
        throw AudDSerializationException("Unparseable response", rawText = rawBody)
    }

    val (rewritten, _) = maybeStripDeprecation(body, onDeprecation)
    when (rewritten["status"]?.jsonPrimitive?.contentOrNull) {
        "error" -> raiseFromErrorResponse(rewritten, httpStatus, requestId, customCatalogContext = false)
        "success" -> return rewritten
        else -> throw AudDServerException(
            errorCode = 0,
            serverMessage = "Unexpected response status: ${rewritten["status"]}",
            httpStatus = httpStatus,
            requestId = requestId,
            rawResponse = rewritten,
        )
    }
}

internal fun maybeStripDeprecation(
    body: JsonObject,
    onDeprecation: DeprecationCallback,
): Pair<JsonObject, Boolean> {
    val err = body["error"] as? JsonObject ?: return body to false
    val code = err["error_code"]?.jsonPrimitive?.intOrNull ?: return body to false
    if (code != DEPRECATED_PARAMS_CODE) return body to false
    val result = body["result"] ?: return body to false
    if (result is kotlinx.serialization.json.JsonNull) return body to false

    val message = err["error_message"]?.jsonPrimitive?.contentOrNull ?: "Deprecated parameter used"
    onDeprecation(message)

    val rewritten = body.toMutableMap().apply {
        remove("error")
        put("status", kotlinx.serialization.json.JsonPrimitive("success"))
    }
    return JsonObject(rewritten) to true
}

private val RECOGNITION_RESULT_KNOWN_KEYS: Set<String> = setOf(
    "timecode", "audio_id", "artist", "title", "album", "release_date", "label",
    "song_link", "apple_music", "spotify", "deezer", "napster", "musicbrainz",
)

private val ENTERPRISE_MATCH_KNOWN_KEYS: Set<String> = setOf(
    "score", "timecode", "artist", "title", "album", "release_date", "label",
    "isrc", "upc", "song_link", "start_offset", "end_offset",
)

/**
 * Parse an enterprise chunk offset into seconds. Accepts `"SS"`, `"MM:SS"`,
 * `"HH:MM:SS"`, or a bare number (string or numeric). Returns `null` on any
 * unparseable input — never throws.
 */
internal fun offsetToSeconds(raw: JsonElement?): Double? {
    if (raw == null || raw is kotlinx.serialization.json.JsonNull) return null
    val text = when (raw) {
        is kotlinx.serialization.json.JsonPrimitive -> raw.contentOrNull
        else -> null
    } ?: return null
    return offsetToSeconds(text)
}

/** String overload: see [offsetToSeconds]. Never throws. */
internal fun offsetToSeconds(text: String?): Double? {
    val trimmed = text?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    if (':' !in trimmed) return trimmed.toDoubleOrNull()
    val parts = trimmed.split(':')
    if (parts.size > 3) return null
    var total = 0.0
    for (part in parts) {
        val value = part.toDoubleOrNull() ?: return null
        total = total * 60.0 + value
    }
    return total
}

/**
 * Decode [obj] into [T], degrading any wrong-typed field to its default
 * (null) instead of throwing.
 *
 * The strict [kotlinx.serialization] decoder throws on a type mismatch — e.g.
 * `score:""` for an `Int?` field, `artist:{}` for a `String?`, or
 * `spotify:"str"` for a `Map`. A successful API response must never surface
 * such a failure to the caller: the invariant is to drop the offending field,
 * not the whole result. We first try the fast path (strict decode of the whole
 * object); only on failure do we identify and prune the keys that don't fit,
 * decoding the survivors. Undecodable-in-isolation keys are removed so the
 * field falls back to its declared default.
 */
internal fun <T> decodeObjectLeniently(
    serializer: DeserializationStrategy<T>,
    obj: JsonObject,
): T {
    // Fast path: nothing wrong-typed.
    runCatching { auddJson.decodeFromJsonElement(serializer, obj) }
        .onSuccess { return it }
    // Slow path: keep only keys that decode cleanly on their own, then decode
    // the survivors. A key that fails in isolation is a genuine type mismatch
    // for its target field (unknown keys are ignored, so they never fail here).
    val kept = obj.filter { (key, value) ->
        runCatching {
            auddJson.decodeFromJsonElement(serializer, JsonObject(mapOf(key to value)))
        }.isSuccess
    }
    val pruned = JsonObject(kept)
    // The survivors decode together (each was individually valid, and the
    // model's fields are independent). If some pathological interaction still
    // fails, fall back to the empty object so every field takes its default.
    return runCatching { auddJson.decodeFromJsonElement(serializer, pruned) }
        .getOrElse { auddJson.decodeFromJsonElement(serializer, JsonObject(emptyMap())) }
}

/** Parse a recognize() response — null = no match. */
internal fun decodeRecognize(
    httpStatus: Int,
    rawBody: String,
    requestId: String?,
    onDeprecation: DeprecationCallback,
): RecognitionResult? {
    val body = decodeOrThrow(httpStatus, rawBody, requestId, onDeprecation)
    val result = body["result"] ?: return null
    if (result is kotlinx.serialization.json.JsonNull) return null
    if (result !is JsonObject) return null
    // Degrade any wrong-typed field to null rather than throwing on a
    // successful response.
    val parsed = decodeObjectLeniently(RecognitionResult.serializer(), result)
    parsed.extras = result.filterKeys { it !in RECOGNITION_RESULT_KNOWN_KEYS }
    return parsed
}

/** Parse a recognizeEnterprise() response — list of EnterpriseMatch (across all chunks). */
internal fun decodeEnterprise(
    httpStatus: Int,
    rawBody: String,
    requestId: String?,
    onDeprecation: DeprecationCallback,
): List<EnterpriseMatch> {
    val body = decodeOrThrow(httpStatus, rawBody, requestId, onDeprecation)
    val resultEl: JsonElement = body["result"] ?: return emptyList()
    if (resultEl !is kotlinx.serialization.json.JsonArray) return emptyList()
    val out = mutableListOf<EnterpriseMatch>()
    for (chunkEl in resultEl) {
        // Skip a chunk that isn't even an object — a broken element must not
        // abort the whole response.
        val chunkObj = chunkEl as? JsonObject ?: continue
        // The chunk `offset` anchors this fragment's position in the user's
        // file. Add it to each song's fragment-relative start/end (ms → s) so
        // callers get absolute file positions in [EnterpriseMatch.startSeconds]
        // / [EnterpriseMatch.endSeconds]. null offset → leave both null.
        val base = offsetToSeconds(chunkObj["offset"])
        val rawSongs = chunkObj["songs"] as? kotlinx.serialization.json.JsonArray ?: continue
        for (songEl in rawSongs) {
            // Decode each song independently: drop non-object / undecodable
            // elements, degrade any wrong-typed field within a song to null.
            val rawSong = songEl as? JsonObject ?: continue
            val song = runCatching {
                decodeObjectLeniently(EnterpriseMatch.serializer(), rawSong)
            }.getOrNull() ?: continue
            val anchored = if (base != null) {
                song.copy(
                    startSeconds = base + (song.startOffset ?: 0.0) / 1000.0,
                    endSeconds = base + (song.endOffset ?: 0.0) / 1000.0,
                )
            } else {
                song
            }
            anchored.extras = rawSong.filterKeys { it !in ENTERPRISE_MATCH_KNOWN_KEYS }
            out.add(anchored)
        }
    }
    return out
}

/** Used by streams.* — decode a generic response and return body["result"] or throw. */
internal fun decodeSuccess(
    httpStatus: Int,
    rawBody: String,
    requestId: String?,
    customCatalogContext: Boolean = false,
): JsonElement? {
    val body: JsonObject? = try {
        auddJson.parseToJsonElement(rawBody) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
    if (body == null) {
        if (httpStatus >= HTTP_CLIENT_ERROR_FLOOR) {
            throw AudDServerException(
                errorCode = 0,
                serverMessage = "HTTP $httpStatus with non-JSON response body",
                httpStatus = httpStatus,
                requestId = requestId,
                rawResponse = null,
            )
        }
        throw AudDSerializationException("Unparseable response", rawText = rawBody)
    }
    when (body["status"]?.jsonPrimitive?.contentOrNull) {
        "error" -> raiseFromErrorResponse(body, httpStatus, requestId, customCatalogContext = customCatalogContext)
        "success" -> return body["result"]
        else -> throw AudDServerException(
            errorCode = 0,
            serverMessage = "Unexpected response status: ${body["status"]}",
            httpStatus = httpStatus,
            requestId = requestId,
            rawResponse = body,
        )
    }
}
