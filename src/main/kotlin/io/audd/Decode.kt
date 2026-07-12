package io.audd

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    // Slow path: a key that fails to decode in isolation is a genuine type
    // mismatch for its target field (unknown keys are ignored, so they never
    // fail here). For each such key we COERCE the wire value toward the field's
    // declared scalar type when convertible — number↔string, float→int
    // truncation, numeric-string→number, the bool table — and only fall back to
    // JsonNull (dropping the field to its default) when no conversion applies.
    val descriptor = serializer.descriptor
    val rewritten = obj.mapValues { (key, value) ->
        val isolatedOk = runCatching {
            auddJson.decodeFromJsonElement(serializer, JsonObject(mapOf(key to value)))
        }.isSuccess
        if (isolatedOk) value else coerceForField(descriptor, key, value)
    }
    val pruned = JsonObject(rewritten)
    // The survivors decode together (each was individually valid or coerced,
    // and the model's fields are independent). If some pathological interaction
    // still fails, fall back to the empty object so every field takes its
    // default.
    return runCatching { auddJson.decodeFromJsonElement(serializer, pruned) }
        .getOrElse { auddJson.decodeFromJsonElement(serializer, JsonObject(emptyMap())) }
}

/**
 * Coerce a wrong-typed wire [value] toward the scalar type declared for [key]
 * in [descriptor], per the family coercion policy. Returns the coerced element
 * when convertible, else [JsonNull] so the field degrades to its default.
 *
 * Only JSON primitives are coerced; objects and arrays for a scalar field —
 * and any field whose declared kind isn't a supported scalar — become null.
 */
private fun coerceForField(
    descriptor: SerialDescriptor,
    key: String,
    value: JsonElement,
): JsonElement {
    val prim = value as? JsonPrimitive ?: return JsonNull
    val index = descriptor.getElementIndex(key)
    if (index == CompositeIndexNotFound) return JsonNull
    // Unwrap nullable/wrapped element descriptors to reach the primitive kind.
    val kind = descriptor.getElementDescriptor(index).kind
    return when (kind) {
        PrimitiveKind.STRING -> coerceToString(prim)
        PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE ->
            coerceToIntegral(prim)
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> coerceToFloating(prim)
        PrimitiveKind.BOOLEAN -> coerceToBoolean(prim)
        else -> JsonNull
    }
}

private const val CompositeIndexNotFound = -3

/** expect STRING: number/bool → rendered string; anything non-primitive → null. */
private fun coerceToString(prim: JsonPrimitive): JsonElement {
    // A quoted primitive (isString) is already a string and would have decoded
    // on the fast path; a bare literal reaches here. Render its content.
    val content = prim.contentOrNull ?: return JsonNull
    return JsonPrimitive(content)
}

/**
 * expect INT/LONG: float/double → truncate; numeric string → parse; bool →
 * 0/1; non-numeric string / anything else → null (never a garbage 0).
 *
 * Numeric-string parsing is full-string strict after trimming: `"85abc"`,
 * `"NaN"`, `"Infinity"` → null.
 */
private fun coerceToIntegral(prim: JsonPrimitive): JsonElement {
    if (prim.booleanOrNullLenient != null) {
        return JsonPrimitive(if (prim.booleanOrNullLenient == true) 1 else 0)
    }
    val content = prim.contentOrNull?.trim() ?: return JsonNull
    if (content.isEmpty()) return JsonNull
    content.toLongOrNull()?.let { return JsonPrimitive(it) }
    // float/double literal or numeric string with a fraction → truncate.
    // Reject non-finite (NaN / Infinity) — never a garbage value.
    strictFiniteDoubleOrNull(content)?.let { return JsonPrimitive(it.toLong()) }
    return JsonNull
}

/**
 * expect FLOAT/DOUBLE: int → convert; numeric string → parse; else null.
 *
 * Full-string strict after trimming; `NaN` / `Infinity` / trailing garbage
 * → null.
 */
private fun coerceToFloating(prim: JsonPrimitive): JsonElement {
    if (prim.booleanOrNullLenient != null) return JsonNull
    val content = prim.contentOrNull?.trim() ?: return JsonNull
    if (content.isEmpty()) return JsonNull
    strictFiniteDoubleOrNull(content)?.let { return JsonPrimitive(it) }
    return JsonNull
}

/**
 * expect BOOL: number → (value != 0); string → a strict case-insensitive,
 * trimmed whitelist both ways:
 * - `"true"` / `"1"` / `"yes"` / `"on"` → true
 * - `"false"` / `"0"` / `"no"` / `"off"` / `""` → false
 * - any other string → null (never a guessed default).
 */
private fun coerceToBoolean(prim: JsonPrimitive): JsonElement {
    val content = prim.contentOrNull ?: return JsonNull
    // A bare numeric literal.
    if (!prim.isString) {
        strictFiniteDoubleOrNull(content)?.let { return JsonPrimitive(it != 0.0) }
        // A bare non-numeric literal that isn't a bool (bools decode on the
        // fast path) — nothing sensible to coerce.
        return JsonNull
    }
    return when (content.trim().lowercase()) {
        "true", "1", "yes", "on" -> JsonPrimitive(true)
        "false", "0", "no", "off", "" -> JsonPrimitive(false)
        else -> JsonNull
    }
}

/**
 * Parse [text] as a finite double, or null. `String.toDoubleOrNull` accepts
 * `"NaN"` / `"Infinity"` / hex-float / trailing `d`/`f` forms — the family
 * policy rejects all non-finite and non-decimal input, so filter explicitly.
 */
private fun strictFiniteDoubleOrNull(text: String): Double? {
    val d = text.toDoubleOrNull() ?: return null
    if (!d.isFinite()) return null
    // Reject the letter-bearing forms toDoubleOrNull tolerates (hex floats,
    // trailing d/f type suffixes) — only plain decimal/scientific numerals pass.
    if (text.any { it.isLetter() && it != 'e' && it != 'E' }) return null
    return d
}

/**
 * Lenient boolean read of a bare literal: `true`/`false` (case-insensitive)
 * only. Quoted strings and everything else → null. Used to detect a JSON
 * boolean before falling back to string/number coercion.
 */
private val JsonPrimitive.booleanOrNullLenient: Boolean?
    get() {
        if (isString) return null
        return when (contentOrNull?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
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
