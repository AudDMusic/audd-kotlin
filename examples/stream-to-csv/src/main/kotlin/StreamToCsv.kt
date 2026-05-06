import io.audd.AudD
import io.audd.AudDApiException
import io.audd.LongpollEvent
import io.audd.StreamCallbackNotification
import io.audd.StreamCallbackResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.PrintWriter
import java.time.Instant

/**
 * Listen for AudD stream-recognition events via longpoll and write each match
 * to a CSV file (one row per result).
 *
 * Two modes:
 *   --url <stream-url> [--radio-id N]   Provision a stream slot, listen, and
 *                                       delete the slot on exit.
 *   --radio-id N                        Listen to an existing slot. Don't add,
 *                                       don't delete.
 */

private const val DEFAULT_OUTPUT = "audd_stream_tracks.csv"
private const val DEFAULT_PROVISION_RADIO_ID = 99999L
private const val EMPTY_CALLBACK_URL = "https://audd.tech/empty/"
private const val NO_CALLBACK_ERROR_CODE = 19
private val CSV_HEADER = listOf(
    "received_at", "radio_id", "timestamp", "score", "artist", "title", "album", "song_link",
)

private data class Args(
    val url: String?,
    val radioId: Long?,
    val output: File,
)

private fun parseArgs(argv: Array<String>): Args {
    var url: String? = null
    var radioId: Long? = null
    var output = File(DEFAULT_OUTPUT)
    var i = 0
    while (i < argv.size) {
        when (val a = argv[i]) {
            "--url" -> {
                require(i + 1 < argv.size) { "--url needs a value" }
                url = argv[i + 1]; i++
            }
            "--radio-id" -> {
                require(i + 1 < argv.size) { "--radio-id needs a value" }
                radioId = argv[i + 1].toLong(); i++
            }
            "--output" -> {
                require(i + 1 < argv.size) { "--output needs a value" }
                output = File(argv[i + 1]); i++
            }
            "-h", "--help" -> { printUsage(); kotlin.system.exitProcess(0) }
            else -> error("Unexpected arg: $a (try --help)")
        }
        i++
    }
    require(url != null || radioId != null) {
        "Either --url (provision-and-listen) or --radio-id (listen-only) is required."
    }
    return Args(url, radioId, output)
}

private fun printUsage() {
    System.err.println(
        """
        Usage:
          stream-to-csv --url <stream-url> [--radio-id N] [--output FILE]
              Provision a new stream slot (radio_id defaults to $DEFAULT_PROVISION_RADIO_ID),
              listen via longpoll, delete the slot on exit.

          stream-to-csv --radio-id N [--output FILE]
              Listen-only against an existing slot. Does not add or delete.

        Both flags together = "add this URL with this explicit radio_id."

        Output defaults to ./$DEFAULT_OUTPUT (append mode).
        AUDD_API_TOKEN must be set in the environment.
        """.trimIndent(),
    )
}

private fun csvEscape(s: String?): String {
    val v = s ?: ""
    return if (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')) {
        "\"" + v.replace("\"", "\"\"") + "\""
    } else {
        v
    }
}

/**
 * Tolerant of unknown server fields — the AudD result schema gains keys over
 * time (timecode, isrc, etc.) and we don't want to break on them.
 */
private val flexJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Longpoll envelope shape (from docs.audd.io/streams):
 *   { "status": "success", "result": { radio_id, timestamp, results: [...] }, "timestamp": ... }
 *   { "status": "-", "notification": { radio_id, notification_code, ... }, "time": ... }
 * The SDK's typed [LongpollEvent] surfaces these inner objects directly via
 * `event.result` and `event.notification`, so we decode each inner object on
 * its own.
 */
private fun handleResultEvent(event: LongpollEvent, csv: PrintWriter) {
    val resultEl = event.result ?: return
    val r = runCatching {
        flexJson.decodeFromJsonElement(StreamCallbackResult.serializer(), resultEl)
    }.getOrElse { exc ->
        System.err.println("[unparseable result] ${exc.message}")
        return
    }
    val receivedAt = Instant.now().toString()
    for (entry in r.results) {
        val row = listOf(
            receivedAt,
            r.radioId.toString(),
            r.timestamp ?: "",
            entry.score.toString(),
            entry.artist,
            entry.title,
            entry.album ?: "",
            entry.songLink ?: "",
        ).joinToString(",") { csvEscape(it) }
        csv.println(row)
        csv.flush()
        println("[match radio=${r.radioId} score=${entry.score}] ${entry.artist} — ${entry.title}")
    }
}

private fun handleNotificationEvent(event: LongpollEvent) {
    val notifEl = event.notification ?: return
    val n = runCatching {
        flexJson.decodeFromJsonElement(StreamCallbackNotification.serializer(), notifEl)
    }.getOrNull()
    if (n != null) {
        System.err.println(
            "[notification radio=${n.radioId} code=${n.notificationCode}] ${n.notificationMessage}",
        )
        return
    }
    // Fall back to whatever shape the server sent.
    val obj = notifEl as? JsonObject
    val code = obj?.get("notification_code")?.jsonPrimitive?.contentOrNull
    val msg = obj?.get("notification_message")?.jsonPrimitive?.contentOrNull
    val radio = obj?.get("radio_id")?.jsonPrimitive?.contentOrNull
    System.err.println("[notification radio=$radio code=$code] $msg")
}

fun main(argv: Array<String>) {
    val args = parseArgs(argv)

    // Manage AudD lifecycle manually rather than via `use { }`. Reason: the JVM
    // shutdown hook (Ctrl-C path) needs to call streams.delete() on the same
    // client, and `use { }` would race with the hook to close it first.
    val audd = AudD(apiToken = null)
    var leftDefaultCallback = false
    var provisionedRadioId: Long? = null
    var csv: PrintWriter? = null

    val shutdownHook = Thread {
        runBlocking {
            runCatching { csv?.flush(); csv?.close() }
            val rid = provisionedRadioId
            if (rid != null) {
                runCatching {
                    audd.streams.delete(rid)
                    println("Deleted stream slot $rid.")
                }.onFailure {
                    System.err.println("Failed to delete stream slot $rid: ${it.message}")
                }
            }
            if (leftDefaultCallback) {
                println(
                    "Note: left $EMPTY_CALLBACK_URL as your account callback — change it via " +
                        "streams.setCallbackUrl(...) if needed.",
                )
            }
            runCatching { audd.close() }
        }
    }
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    try {
        runBlocking {
            // ---- callback URL handling, distinguished by mode ----
            val mode1Provisions = args.url != null
            if (mode1Provisions) {
                leftDefaultCallback = ensureCallbackUrlForProvisionMode(audd)
            } else {
                ensureCallbackUrlForListenOnlyMode(audd)
            }

            // ---- mode 1 only: add the stream slot ----
            if (mode1Provisions) {
                val rid = args.radioId ?: DEFAULT_PROVISION_RADIO_ID
                println("Adding stream radio_id=$rid url=${args.url}")
                audd.streams.add(args.url!!, rid)
                provisionedRadioId = rid
            }

            val effectiveRadioId: Long = provisionedRadioId ?: args.radioId!!

            // ---- open CSV (append; header only if fresh) ----
            val freshFile = !args.output.exists() || args.output.length() == 0L
            val writer = PrintWriter(args.output.outputStream().bufferedWriter())
            csv = writer
            if (freshFile) {
                writer.println(CSV_HEADER.joinToString(","))
                writer.flush()
            }
            println(
                "Writing to ${args.output.absolutePath} " +
                    "(append mode${if (freshFile) ", header written" else ""})",
            )

            // ---- listen ----
            val category = audd.streams.deriveLongpollCategory(effectiveRadioId)
            println("Longpolling category=$category for radio_id=$effectiveRadioId. Ctrl-C to stop.")
            try {
                audd.streams.longpollFlow(category).collect { event ->
                    when {
                        event.isResult -> handleResultEvent(event, writer)
                        event.isNotification -> handleNotificationEvent(event)
                        event.isTimeout -> { /* benign — server-side longpoll cycle */ }
                    }
                }
            } catch (_: CancellationException) {
                // Cooperative cancellation — let the shutdown hook drain.
            } finally {
                writer.flush()
            }
        }
    } finally {
        // Normal-exit cleanup. The shutdown hook handles Ctrl-C; this branch
        // handles a clean return (the longpoll Flow only returns via cancellation,
        // so in practice we get here mainly when an exception escapes runBlocking).
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        runCatching { csv?.flush(); csv?.close() }
        val rid = provisionedRadioId
        if (rid != null) {
            runCatching {
                runBlocking { audd.streams.delete(rid) }
                println("Deleted stream slot $rid.")
            }
        }
        runCatching { audd.close() }
    }
}

/**
 * Mode 1 (provision-and-listen). Returns true iff we set the empty default URL
 * because none was configured (so the shutdown hook can leave a hint about it).
 */
private suspend fun ensureCallbackUrlForProvisionMode(audd: AudD): Boolean {
    return try {
        val current = audd.streams.getCallbackUrl()
        if (current.isBlank()) {
            // Defensive: server returned an empty string instead of #19. Treat as unset.
            audd.streams.setCallbackUrl(EMPTY_CALLBACK_URL)
            println("longpoll requires any 200-OK URL server-side; using audd.tech/empty/ as a default.")
            true
        } else {
            println("Account callback URL already configured (left as-is).")
            false
        }
    } catch (exc: AudDApiException) {
        if (exc.errorCode == NO_CALLBACK_ERROR_CODE) {
            audd.streams.setCallbackUrl(EMPTY_CALLBACK_URL)
            println("longpoll requires any 200-OK URL server-side; using audd.tech/empty/ as a default.")
            true
        } else {
            throw exc
        }
    }
}

/**
 * Mode 2 (listen-only). Refuses to start when no callback URL is set — longpoll
 * won't deliver without one, and we can't silently set it because the slot
 * already belongs to the account-as-configured.
 */
private suspend fun ensureCallbackUrlForListenOnlyMode(audd: AudD) {
    try {
        val current = audd.streams.getCallbackUrl()
        if (current.isBlank()) {
            error(
                "Stream slot exists but no callback URL is configured for this account; longpoll won't deliver. " +
                    "Set one first via streams.setCallbackUrl(...).",
            )
        }
    } catch (exc: AudDApiException) {
        if (exc.errorCode == NO_CALLBACK_ERROR_CODE) {
            error(
                "Stream slot exists but no callback URL is configured for this account; longpoll won't deliver. " +
                    "Set one first via streams.setCallbackUrl(...).",
            )
        }
        throw exc
    }
}
