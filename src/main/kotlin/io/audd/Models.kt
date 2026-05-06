package io.audd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * Project-wide JSON parser. Tolerates unknown server fields so the SDK
 * stays forward-compatible — the AudD API adds new metadata fields all the
 * time and we don't want a release to be required to keep parsing.
 */
internal val auddJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Streaming providers reachable via the lis.tn `?<provider>` redirect helper
 * and (for the four with metadata blocks) directly from the response.
 *
 * `wireName` is the lower-snake-case identifier used in URL query params and
 * metadata-block keys.
 */
public enum class StreamingProvider(public val wireName: String) {
    SPOTIFY("spotify"),
    APPLE_MUSIC("apple_music"),
    DEEZER("deezer"),
    NAPSTER("napster"),
    YOUTUBE("youtube"),
}

@Serializable
public data class MusicBrainzEntry(
    val id: String,
    val title: String? = null,
    val length: Long? = null,
)

/**
 * A single recognition result.
 *
 * - When [audioId] is non-null this is a custom-catalog match (your own
 *   fingerprinted audio).
 * - When [audioId] is null and [artist] / [title] are present this is a
 *   public-catalog match.
 *
 * For exhaustive `when` pattern-matching, project to [RecognitionMatch].
 *
 * The four metadata blocks ([appleMusic], [spotify], [deezer], [napster]) are
 * exposed as `Map<String, JsonElement>?` so the SDK stays forward-compatible
 * with new keys the AudD/provider APIs add. Use [streamingUrl] / [previewUrl]
 * for the common lookups, or read the keys directly via the maps.
 */
@Serializable
public data class RecognitionResult(
    val timecode: String,
    @SerialName("audio_id") val audioId: Long? = null,
    val artist: String? = null,
    val title: String? = null,
    val album: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val label: String? = null,
    @SerialName("song_link") val songLink: String? = null,
    val isrc: String? = null,
    val upc: String? = null,
    @SerialName("apple_music") val appleMusic: Map<String, JsonElement>? = null,
    val spotify: Map<String, JsonElement>? = null,
    val deezer: Map<String, JsonElement>? = null,
    val napster: Map<String, JsonElement>? = null,
    val musicbrainz: List<MusicBrainzEntry>? = null,
) {
    /**
     * Server-side fields that aren't part of the typed surface — beta features,
     * per-account custom fields, and anything AudD adds before this SDK gets a
     * matching property.
     *
     * Populated only when the result is parsed via the SDK's recognize path
     * (the [Json] decoder ignores unknown keys; the SDK's own decode step
     * captures them). Empty for results constructed manually or via direct
     * `Json.decodeFromJsonElement`.
     *
     * Keys are the server's wire names (snake_case). Values are raw
     * [JsonElement]s — call `.jsonPrimitive.contentOrNull` for a string,
     * `.jsonObject` / `.jsonArray` for nested structures.
     */
    public var extras: Map<String, JsonElement> = emptyMap()
        internal set

    public val isCustomMatch: Boolean get() = audioId != null
    public val isPublicMatch: Boolean get() = audioId == null && (artist != null || title != null)

    /**
     * Cover-art URL for `lis.tn`-hosted song_links, else `null`.
     *
     * The SDK appends `?thumb` (or `&thumb`) — only valid for hosts where
     * AudD's image endpoint exists. YouTube and other hosts return `null`.
     */
    public val thumbnailUrl: String?
        get() = lisTnStreamingUrl(songLink, "thumb")

    /**
     * Direct or redirect URL for a streaming provider, with smart fallback.
     *
     * Resolution order:
     * 1. **Direct URL from the metadata block** when the user requested that
     *    provider via `return=` (e.g. `apple_music["url"]`,
     *    `spotify["external_urls"]["spotify"]`, `deezer["link"]`,
     *    `napster["href"]`). Direct = no redirect, faster for clients.
     * 2. **lis.tn redirect** `"$songLink?$provider"` when [songLink] is a
     *    lis.tn URL. Works regardless of whether `return=` was set.
     * 3. `null` when neither path resolves.
     *
     * `StreamingProvider.YOUTUBE` has only the lis.tn-redirect path.
     */
    public fun streamingUrl(provider: StreamingProvider): String? {
        val direct = directStreamingUrl(provider)
        if (direct != null) return direct
        return lisTnStreamingUrl(songLink, provider.wireName)
    }

    private fun directStreamingUrl(provider: StreamingProvider): String? = when (provider) {
        StreamingProvider.APPLE_MUSIC -> appleMusic?.let { stringField(it, "url") }
        StreamingProvider.SPOTIFY -> spotify?.let { sp ->
            val ext = sp["external_urls"] as? JsonObject
            stringField(ext, "spotify") ?: stringField(sp, "uri")
        }
        StreamingProvider.DEEZER -> deezer?.let { stringField(it, "link") }
        StreamingProvider.NAPSTER -> napster?.let { stringField(it, "href") }
        // YouTube has no metadata block — only the lis.tn redirect path.
        StreamingProvider.YOUTUBE -> null
    }

    /**
     * All providers with a resolvable URL — direct or via lis.tn redirect.
     *
     * Empty when no metadata block carries a URL and [songLink] is not a
     * lis.tn URL.
     */
    public fun streamingUrls(): Map<StreamingProvider, String> {
        val out = LinkedHashMap<StreamingProvider, String>()
        for (p in StreamingProvider.values()) {
            val url = streamingUrl(p)
            if (url != null) out[p] = url
        }
        return out
    }

    /**
     * First available 30-second audio preview URL, in priority order:
     * `apple_music.previews[0].url` → `spotify.preview_url` → `deezer.preview`.
     *
     * **Note:** previews are governed by the respective providers' terms of
     * use (Apple Music, Spotify, Deezer). The SDK consumer is responsible for
     * honoring those terms — including caching restrictions, attribution
     * requirements, and any redistribution constraints.
     */
    public fun previewUrl(): String? {
        val applePreviews = (appleMusic?.get("previews") as? JsonArray)
        if (applePreviews != null && applePreviews.isNotEmpty()) {
            val first = applePreviews[0] as? JsonObject
            val url = stringField(first, "url")
            if (!url.isNullOrEmpty()) return url
        }
        val spurl = stringField(spotify, "preview_url")
        if (!spurl.isNullOrEmpty()) return spurl
        val dz = stringField(deezer, "preview")
        if (!dz.isNullOrEmpty()) return dz
        return null
    }

    /** Project this result onto the sealed [RecognitionMatch] sum type. */
    public fun toMatch(): RecognitionMatch = if (audioId != null) {
        RecognitionMatch.Custom(this)
    } else {
        RecognitionMatch.Public(this)
    }
}

/**
 * Sealed projection over [RecognitionResult] for exhaustive `when` branches.
 *
 * Use:
 * ```kotlin
 * when (val match = result.toMatch()) {
 *   is RecognitionMatch.Public -> ... match.value.artist ...
 *   is RecognitionMatch.Custom -> ... match.value.audioId ...
 * }
 * ```
 */
public sealed interface RecognitionMatch {
    public val value: RecognitionResult

    public data class Public(override val value: RecognitionResult) : RecognitionMatch
    public data class Custom(override val value: RecognitionResult) : RecognitionMatch
}

@Serializable
public data class EnterpriseMatch(
    val score: Int,
    val timecode: String,
    val artist: String? = null,
    val title: String? = null,
    val album: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val label: String? = null,
    val isrc: String? = null,
    val upc: String? = null,
    @SerialName("song_link") val songLink: String? = null,
    @SerialName("start_offset") val startOffset: Double? = null,
    @SerialName("end_offset") val endOffset: Double? = null,
) {
    /**
     * Server-side fields outside the typed surface. See [RecognitionResult.extras].
     */
    public var extras: Map<String, JsonElement> = emptyMap()
        internal set

    /** Cover-art URL for `lis.tn`-hosted song_links, else `null`. */
    public val thumbnailUrl: String?
        get() = lisTnStreamingUrl(songLink, "thumb")

    /**
     * lis.tn redirect URL for the given streaming provider.
     *
     * Enterprise responses carry no metadata blocks, so the only path is the
     * lis.tn redirect. Returns `null` when [songLink] is missing or off-host.
     */
    public fun streamingUrl(provider: StreamingProvider): String? =
        lisTnStreamingUrl(songLink, provider.wireName)

    /** All providers' lis.tn redirect URLs. Empty when [songLink] is non-lis.tn. */
    public fun streamingUrls(): Map<StreamingProvider, String> {
        val out = LinkedHashMap<StreamingProvider, String>()
        for (p in StreamingProvider.values()) {
            val url = lisTnStreamingUrl(songLink, p.wireName)
            if (url != null) out[p] = url
        }
        return out
    }
}

@Serializable
internal data class EnterpriseChunkResult(
    val songs: List<EnterpriseMatch>,
    val offset: String,
)

@Serializable
public data class Stream(
    @SerialName("radio_id") val radioId: Long,
    val url: String,
    @SerialName("stream_running") val streamRunning: Boolean,
    @SerialName("longpoll_category") val longpollCategory: String? = null,
)

@Serializable
public data class StreamCallbackResultEntry(
    val artist: String,
    val title: String,
    val score: Int,
    val album: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val label: String? = null,
    @SerialName("song_link") val songLink: String? = null,
    @SerialName("apple_music") val appleMusic: Map<String, JsonElement>? = null,
    val spotify: Map<String, JsonElement>? = null,
    val deezer: Map<String, JsonElement>? = null,
    val napster: Map<String, JsonElement>? = null,
    val musicbrainz: List<MusicBrainzEntry>? = null,
)

@Serializable
public data class StreamCallbackResult(
    @SerialName("radio_id") val radioId: Long,
    val timestamp: String? = null,
    @SerialName("play_length") val playLength: Int? = null,
    val results: List<StreamCallbackResultEntry>,
)

@Serializable
public data class StreamCallbackNotification(
    @SerialName("radio_id") val radioId: Long,
    @SerialName("stream_running") val streamRunning: Boolean? = null,
    @SerialName("notification_code") val notificationCode: Int,
    @SerialName("notification_message") val notificationMessage: String,
)

/**
 * Wrapper over a callback POST body — either a recognition result or a
 * server notification (e.g. "can't connect to the audiostream").
 */
public class StreamCallbackPayload internal constructor(
    public val result: StreamCallbackResult?,
    public val notification: StreamCallbackNotification?,
    public val time: Long?,
    public val rawPayload: JsonObject,
) {
    public val isResult: Boolean get() = result != null
    public val isNotification: Boolean get() = notification != null

    public companion object {
        public fun parse(body: JsonObject): StreamCallbackPayload {
            val notif = body["notification"]
            if (notif is JsonObject) {
                return StreamCallbackPayload(
                    result = null,
                    notification = auddJson.decodeFromJsonElement(StreamCallbackNotification.serializer(), notif),
                    time = body["time"]?.jsonPrimitive?.intOrNull?.toLong(),
                    rawPayload = body,
                )
            }
            val inner = body["result"] as? JsonObject ?: JsonObject(emptyMap())
            return StreamCallbackPayload(
                result = auddJson.decodeFromJsonElement(StreamCallbackResult.serializer(), inner),
                notification = null,
                time = null,
                rawPayload = body,
            )
        }
    }
}

@Serializable
public data class LyricsResult(
    val artist: String,
    val title: String,
    val lyrics: String? = null,
    @SerialName("song_id") val songId: Long? = null,
    val media: String? = null,
    @SerialName("full_title") val fullTitle: String? = null,
    @SerialName("artist_id") val artistId: Long? = null,
    @SerialName("song_link") val songLink: String? = null,
)

/** A single longpoll envelope as delivered by the server. */
@Serializable
public data class LongpollEvent(
    val timestamp: Long? = null,
    val timeout: String? = null,
    val notification: JsonElement? = null,
    val result: JsonElement? = null,
    val time: Long? = null,
    val status: String? = null,
) {
    public val isTimeout: Boolean get() = timeout != null
    public val isNotification: Boolean get() = notification != null
    public val isResult: Boolean get() = result != null
}

// ---- internal helpers shared by streaming-URL helpers ----

/** Append `?<param>` (or `&<param>`) to [songLink] only if it's a lis.tn URL. */
internal fun lisTnStreamingUrl(songLink: String?, param: String): String? {
    if (songLink.isNullOrEmpty()) return null
    val parsed = runCatching { URI(songLink) }.getOrNull() ?: return null
    if (parsed.host != "lis.tn") return null
    val sep = if (parsed.query.isNullOrEmpty()) "?" else "&"
    return "$songLink${sep}$param"
}

/** Read a string field from a metadata-block map, returning null on missing or non-string. */
internal fun stringField(map: Map<String, JsonElement>?, key: String): String? {
    if (map == null) return null
    val el = map[key] ?: return null
    if (el is JsonNull) return null
    val prim = el as? JsonPrimitive ?: return null
    if (!prim.isString) return null
    return prim.contentOrNull
}
