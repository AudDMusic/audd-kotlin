package io.audd

import kotlinx.serialization.json.JsonObject
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

/** Parse a callback POST body into a typed [StreamCallbackPayload]. */
public fun parseCallback(body: JsonObject): StreamCallbackPayload =
    StreamCallbackPayload.parse(body)

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
