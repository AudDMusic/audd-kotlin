package io.audd

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Compute the 9-character longpoll category locally from the api_token + radio_id.
 *
 * Formula (per https://docs.audd.io/streams.md): hex-MD5 of
 * (hex-MD5(api_token) concatenated with the decimal radio_id), truncated to 9 chars.
 */
public fun deriveLongpollCategory(apiToken: String, radioId: Long): String {
    val md5 = MessageDigest.getInstance("MD5")
    val inner = md5.digest(apiToken.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    md5.reset()
    val outer = md5.digest((inner + radioId.toString()).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return outer.substring(0, 9)
}

/**
 * Parse a callback POST body into a typed [CallbackEvent]. Exactly one
 * of [CallbackEvent.Match] / [CallbackEvent.Notification] is
 * returned on success.
 *
 * Recognition callbacks have an outer `result` block; notification callbacks
 * have an outer `notification` block; the discrimination is by-key.
 */
public fun parseCallback(body: JsonObject): CallbackEvent {
    val notif = body["notification"]
    if (notif is JsonObject) {
        // A successful notification callback must never throw on a wrong-typed
        // field — degrade the offending field to null.
        val parsed = decodeObjectLeniently(StreamCallbackNotification.serializer(), notif)
        // The outer `time` field lives on the envelope, not the notification block.
        val outerTime = body["time"]?.jsonPrimitive?.intOrNull
        val final = if (outerTime != null && parsed.time == null) parsed.copy(time = outerTime) else parsed
        final.rawResponse = body
        // Notification extras: anything in the inner `notification` block we
        // didn't already type. Cheap enough to extract here.
        final.extras = notif.filterKeys { it !in NOTIFICATION_KNOWN_KEYS }
        return CallbackEvent.Notification(final)
    }
    val resultEl = body["result"]
    if (resultEl is JsonObject) {
        return CallbackEvent.Match(parseMatch(resultEl, body))
    }
    throw AudDSerializationException(
        "callback body has neither result nor notification",
        rawText = body.toString(),
    )
}

/**
 * Convenience overload: parse a raw byte-array callback body. Useful from
 * any HTTP server framework — read the request body bytes, hand them in.
 */
public fun parseCallback(body: ByteArray): CallbackEvent =
    parseCallback(body.toString(Charsets.UTF_8))

/**
 * Convenience overload: parse a raw string callback body.
 */
public fun parseCallback(body: String): CallbackEvent {
    val element = try {
        auddJson.parseToJsonElement(body)
    } catch (e: SerializationException) {
        throw AudDSerializationException(
            "callback body is not valid JSON: ${e.message}",
            rawText = body,
            cause = e,
        )
    }
    val obj = element as? JsonObject
        ?: throw AudDSerializationException("callback body is not a JSON object", rawText = body)
    return parseCallback(obj)
}

/**
 * Decode a `result` block into a [StreamCallbackMatch]. The block's `results`
 * array becomes [StreamCallbackMatch.song] (first entry) +
 * [StreamCallbackMatch.alternatives] (remaining).
 */
private fun parseMatch(resultEl: JsonObject, fullBody: JsonObject): StreamCallbackMatch {
    // A successful callback must never throw because a field is absent or the
    // wrong type. Missing radio_id → null; missing/empty results → song = null.
    val radioId = resultEl["radio_id"]?.jsonPrimitive?.let { it.intOrNull?.toLong() ?: it.toString().toLongOrNull() }
    val timestamp = resultEl["timestamp"]?.jsonPrimitive?.contentOrNullSafe()
    val playLength = resultEl["play_length"]?.jsonPrimitive?.intOrNull
    val resultsEl: List<kotlinx.serialization.json.JsonElement> =
        resultEl["results"] as? kotlinx.serialization.json.JsonArray ?: emptyList()
    // Decode each candidate; skip non-object entries, and degrade any
    // wrong-typed field within a song to null rather than dropping the song.
    val songs = resultsEl.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val parsed = runCatching {
            decodeObjectLeniently(StreamCallbackSong.serializer(), obj)
        }.getOrNull() ?: return@mapNotNull null
        parsed.extras = obj.filterKeys { it !in STREAM_CALLBACK_SONG_KNOWN_KEYS }
        parsed
    }
    val match = StreamCallbackMatch(
        radioId = radioId,
        timestamp = timestamp,
        playLength = playLength,
        song = songs.firstOrNull(),
        alternatives = if (songs.size > 1) songs.subList(1, songs.size) else emptyList(),
    )
    match.extras = resultEl.filterKeys { it !in STREAM_CALLBACK_MATCH_KNOWN_KEYS }
    match.rawResponse = fullBody
    return match
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content

private val STREAM_CALLBACK_MATCH_KNOWN_KEYS: Set<String> = setOf(
    "radio_id", "timestamp", "play_length", "results",
)

private val STREAM_CALLBACK_SONG_KNOWN_KEYS: Set<String> = setOf(
    "artist", "title", "score", "album", "release_date", "label", "song_link",
    "isrc", "upc", "apple_music", "spotify", "deezer", "napster", "musicbrainz",
)

private val NOTIFICATION_KNOWN_KEYS: Set<String> = setOf(
    "radio_id", "stream_running", "notification_code", "notification_message",
)

/**
 * Append `?return=<metadata>` (or merge as `&return=`) to the callback URL.
 *
 * If [returnMetadata] is null, return the URL unchanged.
 * If the URL already has a `return` query parameter, throws
 * [AudDInvalidRequestException] to avoid silently overwriting it.
 */
public fun addReturnToUrl(url: String, returnMetadata: Any?): String {
    if (returnMetadata == null) return url
    val asString = when (returnMetadata) {
        is String -> returnMetadata
        is List<*> -> returnMetadata.joinToString(",") { it.toString() }
        else -> returnMetadata.toString()
    }

    val parsed = URI(url)
    val query = parsed.rawQuery ?: ""
    val params = if (query.isEmpty()) emptyList() else query.split("&").map { kv ->
        val idx = kv.indexOf('=')
        if (idx < 0) kv to "" else kv.substring(0, idx) to kv.substring(idx + 1)
    }
    if (params.any { it.first == "return" }) {
        throw AudDInvalidRequestException(
            errorCode = 0,
            serverMessage = "URL already contains a `return` query parameter; pass returnMetadata=null " +
                "or remove the parameter from the URL — refusing to silently overwrite.",
            httpStatus = 0,
            requestId = null,
        )
    }
    val encoded = URLEncoder.encode(asString, Charsets.UTF_8.name())
    val newQuery = if (query.isEmpty()) "return=$encoded" else "$query&return=$encoded"
    return URI(
        parsed.scheme,
        parsed.userInfo,
        parsed.host,
        parsed.port,
        parsed.path,
        null,
        parsed.fragment,
    ).toString().let { base ->
        // URI's constructor re-encoded our query, so we splice it manually.
        val withQuery = if (parsed.fragment != null) {
            base.substringBefore("#") + "?" + newQuery + "#" + parsed.fragment
        } else {
            base + "?" + newQuery
        }
        withQuery
    }
}
