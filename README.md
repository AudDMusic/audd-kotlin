# audd-kotlin

[![CI](https://github.com/AudDMusic/audd-kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/AudDMusic/audd-kotlin/actions/workflows/ci.yml)
[![Contract](https://github.com/AudDMusic/audd-kotlin/actions/workflows/contract.yml/badge.svg)](https://github.com/AudDMusic/audd-kotlin/actions/workflows/contract.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.audd/audd-kotlin.svg)](https://central.sonatype.com/artifact/io.audd/audd-kotlin)

Official Kotlin SDK for [music recognition API](https://audd.io): identify music from a short audio clip, a long audio file, or a live stream.

The API itself is so simple that it can easily be used even without an SDK: [docs.audd.io](https://docs.audd.io).

## Quickstart

```kotlin
implementation("io.audd:audd-kotlin:1.5.7")
```

Get your API token at [dashboard.audd.io](https://dashboard.audd.io).

Recognize from a URL:

```kotlin
import io.audd.AudD
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    AudD("your-api-token").use { audd ->
        val result = audd.recognize("https://audd.tech/example.mp3")
        if (result != null) println("${result.artist} — ${result.title}")
    }
}
```

Recognize from a local file:

```kotlin
import io.audd.AudD
import io.audd.Source
import java.io.File
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    AudD("your-api-token").use { audd ->
        val result = audd.recognize(Source.FilePath(File("/path/to/clip.mp3")))
        if (result != null) println("${result.artist} — ${result.title}")
    }
}
```

`recognize` accepts a URL string directly, or a `Source` — `Source.Url`, `Source.FilePath`, `Source.Bytes`, or `Source.Stream`. It returns a `RecognitionResult` on a match, or `null` when the clip isn't recognized.

For files longer than 25 seconds (broadcasts, podcasts, full DJ sets), use `recognizeEnterprise(source, limit = ...)` — it returns a `List<EnterpriseMatch>`, one per song detected across the file.

## Authentication

Pass the token positionally:

```kotlin
val audd = AudD("your-token")
```

Or omit it and set `AUDD_API_TOKEN` in the environment — the SDK reads it on construction. `AudD.fromEnvironment()` is the discoverable factory for the same fallback:

```kotlin
val audd = AudD.fromEnvironment()
```

For long-running services that rotate tokens (e.g. from a secret manager), call `audd.setApiToken(newToken)`. In-flight requests finish on the previous token; subsequent requests use the new one. Safe to call from any thread.

## What you get back

By default `recognize` returns the core tags plus AudD's universal song link — no metadata-block opt-in needed:

```kotlin
import io.audd.AudD
import io.audd.StreamingProvider
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    AudD.fromEnvironment().use { audd ->
        val result = audd.recognize("https://audd.tech/example.mp3") ?: error("no match")

        // Core tags
        println("${result.artist} — ${result.title}")
        println("${result.album} ${result.releaseDate} ${result.label}")

        // AudD's universal song page — links into every provider
        println(result.songLink)

        // Helpers — driven off songLink, work without any returnExtras opt-in
        println(result.thumbnailUrl)                              // cover-art URL, or null
        println(result.streamingUrl(StreamingProvider.SPOTIFY))   // direct or lis.tn redirect, or null
        println(result.streamingUrls())                           // Map<StreamingProvider, String>
    }
}
```

If you need provider-specific metadata blocks, opt in per call. Request only what you need — each provider you ask for adds latency:

```kotlin
val result = audd.recognize(
    "https://audd.tech/example.mp3",
    returnExtras = listOf("apple_music", "spotify"),
)
println(result?.appleMusic?.get("url"))    // direct Apple Music link
println(result?.spotify?.get("uri"))       // spotify:track:...
println(result?.previewUrl())              // first preview across requested providers, or null
```

Valid `returnExtras` values: `apple_music`, `spotify`, `deezer`, `napster`, `musicbrainz`. The corresponding properties (`appleMusic`, `spotify`, `deezer`, `napster`, `musicbrainz`) are `null` when not requested.

`EnterpriseMatch` (returned by `recognizeEnterprise`) carries the same core tags plus `score`, `startOffset`, `endOffset`, `isrc`, `upc`. Access to `isrc`, `upc`, and `score` requires a Startup plan or higher — [contact us](mailto:api@audd.io) for enterprise features.

For exhaustive `when` on the two match flavours (public-catalog vs. your custom catalog), project to the sealed `RecognitionMatch`:

```kotlin
when (val match = result.toMatch()) {
    is RecognitionMatch.Public -> println("public: ${match.value.artist}")
    is RecognitionMatch.Custom -> println("custom audio_id=${match.value.audioId}")
}
```

## Reading additional metadata

The typed models cover what AudD documents. To read undocumented or beta fields the server returns, go through `extras`:

```kotlin
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Top-level extras outside the typed surface
val genre = result.extras["genre"]?.jsonPrimitive?.contentOrNull

// Nested extras inside a typed metadata block (the block itself is already
// a Map<String, JsonElement>, so unknown fields are first-class)
val artwork = result.appleMusic?.get("artwork")
```

This is the supported API for fields outside the typed surface. Beta features and per-account custom fields show up here.

## Errors

Every server-side error becomes a typed exception. The hierarchy lets you handle whole families with one `catch` and gives `when` exhaustiveness on `AudDApiException` subtypes:

```
AudDException
├── AudDConnectionException         # network / TLS / timeout
├── AudDSerializationException      # malformed JSON
└── AudDApiException                # status=error from server
    ├── AudDAuthenticationException     # 900 / 901 / 903
    ├── AudDQuotaException              # 902
    ├── AudDSubscriptionException       # 904 / 905
    │   └── AudDCustomCatalogAccessException  # 904 from customCatalog
    ├── AudDInvalidRequestException     # 50 / 51 / 600 / 601 / 602 / 700–702 / 906
    ├── AudDInvalidAudioException       # 300 / 400 / 500
    ├── AudDStreamLimitException        # 610
    ├── AudDRateLimitException          # 611
    ├── AudDNotReleasedException        # 907
    ├── AudDBlockedException            # 19 / 31337
    ├── AudDNeedsUpdateException        # 20
    └── AudDServerException             # 100 / 1000 / unknown
```

Idiomatic catch via `when`:

```kotlin
import io.audd.*

try {
    val result = audd.recognize("https://example.mp3")
} catch (e: AudDException) {
    when (e) {
        is AudDAuthenticationException -> error("check your token: [#${e.errorCode}] ${e.serverMessage}")
        is AudDInvalidAudioException   -> println("audio rejected: ${e.serverMessage}")
        is AudDApiException            -> println("AudD #${e.errorCode}: ${e.serverMessage} (request_id=${e.requestId})")
        is AudDConnectionException     -> println("connection: ${e.message}")
        is AudDSerializationException  -> println("bad payload: ${e.message}")
    }
}
```

Every `AudDApiException` carries `errorCode`, `serverMessage`, `httpStatus`, `requestId`, `requestedParams`, `requestMethod`, `brandedMessage`, and `rawResponse` — enough to log a full incident or open a support ticket.

## Configuration

```kotlin
import io.audd.AudD
import io.audd.AudDEvent

val audd = AudD(
    apiToken = "your-token",
    maxRetries = 3,                 // per-call retry budget
    backoffFactor = 0.5,            // initial backoff seconds (jittered)
    onEvent = { e: AudDEvent -> println(e) },
)
```

**Timeouts.** Default Ktor timeouts are 30s connect / 60s read for standard endpoints, and 30s connect / 1 hour read for the enterprise endpoint (which can legitimately process multi-hour files). Inject your own `HttpClient` or `HttpClientEngine` to override.

**Retries.** Calls are classified by cost and retried accordingly:

| Class         | Endpoints                                                 | Retried on                                               |
|---------------|-----------------------------------------------------------|----------------------------------------------------------|
| `RECOGNITION` | `recognize`, `recognizeEnterprise`, `advanced.*`          | network errors and 5xx **before** the upload reaches the server |
| `READ`        | `streams.list`, `streams.getCallbackUrl`, longpoll        | network errors and 5xx                                   |
| `MUTATING`    | `streams.setCallbackUrl`, `streams.add`, `streams.delete` | pre-upload connection errors only (server-idempotent on `radio_id`) |
| `CRITICAL`    | `customCatalog.add`                                       | never — exactly one attempt                              |

`RECOGNITION` will not double-bill your account: once the server has accepted bytes, a 5xx after that is surfaced rather than retried. `customCatalog.add` is metered and uses the `CRITICAL` class — a transient failure surfaces as a clean exception so an automatic re-upload can never double-charge for the same audio fingerprinting.

**Custom HTTP client.** Pass `engine = ` (`HttpClientEngine`) or `httpClient = ` (a fully-configured `HttpClient`) to plug in proxies, mTLS, custom transports, or shared connection pools.

**Inspection.** Pass `onEvent = ` to receive an immutable `AudDEvent` for every request / response / exception — useful for metrics, tracing, or dropping a `requestId` into your logs. Events never carry the api_token or request bytes; exceptions raised from the hook are swallowed so observability can't break the request path.

A single `AudD` instance is safe to share across coroutines — construct it once at startup and reuse it. Always `close()` (or use `use { }`) to release the underlying HTTP transports.

## Streams

Real-time recognition off radio streams, broadcast feeds, and any other long-running URL. Configure once, then either receive callbacks on your server or longpoll for events.

```kotlin
audd.streams.setCallbackUrl("https://your.server/audd-callback")
audd.streams.add("https://your.stream.url/listen.m3u8", radioId = 42L)

for (stream in audd.streams.list()) {
    println("${stream.radioId} ${stream.url} running=${stream.streamRunning}")
}
```

Inside your webhook handler, parse the request body into a typed result:

```kotlin
import io.audd.CallbackEvent
import io.audd.parseCallback

// `bodyBytes: ByteArray` is the raw POST body — this works from any HTTP
// server framework (ktor-server, Spring, Javalin, vert.x, whatever).
when (val parsed = parseCallback(bodyBytes)) {
    is CallbackEvent.Match -> {
        val m = parsed.match
        println("matched: ${m.song.artist} — ${m.song.title} score=${m.song.score}")
        for (alt in m.alternatives) {
            // Rare. Variant catalog releases (clean / explicit / single vs album)
            // can land here with a different artist or title.
            println("  alt: ${alt.artist} — ${alt.title}")
        }
    }
    is CallbackEvent.Notification -> {
        val n = parsed.notification
        println("notification radio=${n.radioId} code=${n.notificationCode}: ${n.notificationMessage}")
    }
}
```

`parseCallback` accepts `ByteArray`, `String`, or an already-parsed `JsonObject` — pick whichever your framework hands you.

### Longpoll

If you can't expose a public callback URL, longpoll instead. AudD still requires a callback URL to be configured for the account (`https://audd.tech/empty/` works as a no-op receiver), and the SDK preflights this for you — pass `LongpollOptions(skipCallbackCheck = true)` to skip if you've already verified.

`streams.longpoll(category)` returns a `LongpollPoll` with three typed flows — `matches`, `notifications`, `errors`. Collect each in its own coroutine:

```kotlin
import io.audd.AudD
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    AudD.fromEnvironment().use { audd ->
        val radioId = 1L // any integer you choose — your handle for this stream
        audd.streams.longpoll(radioId).use { poll ->
            coroutineScope {
                launch { poll.matches.collect { m -> println("${m.song.artist} — ${m.song.title}") } }
                launch { poll.notifications.collect { n -> println("notif: ${n.notificationMessage}") } }
                launch { poll.errors.collect { err -> throw err } }
            }
        }
    }
}
```

The poll advances `since_time` automatically across iterations. Errors are terminal: when one fires on `errors`, the producer stops and all three flows complete. `poll.close()` (or exiting `use { }`) cancels the producer.

`deriveLongpollCategory` is a local computation: `MD5(MD5(api_token) + radio_id)[:9]`. The category alone is sufficient to subscribe — the api_token is never sent over the wire for longpolls.

#### Tokenless consumers

For browser widgets, embedded extensions, or any context where shipping the api_token would leak it: derive the category server-side, ship only the category to the consumer, and have the consumer use `LongpollConsumer`. Same `LongpollPoll` shape as above:

```kotlin
import io.audd.LongpollConsumer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // `category` was derived on your server and shared with this process.
    LongpollConsumer(category = "abc123def").use { consumer ->
        consumer.iterate().use { poll ->
            coroutineScope {
                launch { poll.matches.collect { m -> println("${m.song.artist} — ${m.song.title}") } }
                launch { poll.notifications.collect { n -> println(n.notificationMessage) } }
                launch { poll.errors.collect { err -> throw err } }
            }
        }
    }
}
```

## Custom catalog (advanced)

> **The custom-catalog endpoint is NOT how you submit audio for music recognition.**
> For recognition, use `recognize` (or `recognizeEnterprise` for files longer than 25 seconds). The custom-catalog endpoint adds songs to your *private* fingerprint database so future `recognize` calls on your account can identify *your own* tracks.
> Requires special access — contact api@audd.io.

```kotlin
audd.customCatalog.add(audioId = 42L, source = Source.Url("https://my.song.mp3"))
```

## License

MIT — see [LICENSE](./LICENSE).

## Support

- Documentation: <https://docs.audd.io>
- Tokens: <https://dashboard.audd.io>
- Issues: <https://github.com/AudDMusic/audd-kotlin/issues>
- Email: api@audd.io
