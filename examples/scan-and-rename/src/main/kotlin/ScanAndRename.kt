import io.audd.AudD
import io.audd.RecognitionResult
import io.audd.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Walk a folder of audio files, recognize each via the AudD API, write tags
 * back to the file via jaudiotagger, then rename to "Artist - Title.ext".
 *
 * Default mode is dry-run — pass --apply to actually write tags and rename.
 */

private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "opus", "m4a", "mp4", "wav", "aac")
private val ILLEGAL_FILENAME_CHARS = Regex("""[/\\:*?"<>|]""")
private const val MAX_FILENAME_BASE = 200

private data class Args(
    val folder: File,
    val apply: Boolean,
    val concurrency: Int,
)

private fun parseArgs(argv: Array<String>): Args {
    var folder: File? = null
    var apply = false
    var concurrency = 4
    var i = 0
    while (i < argv.size) {
        when (val a = argv[i]) {
            "--apply" -> apply = true
            "--concurrency" -> {
                require(i + 1 < argv.size) { "--concurrency needs a value" }
                concurrency = argv[i + 1].toInt()
                i++
            }
            "-h", "--help" -> {
                printUsage()
                kotlin.system.exitProcess(0)
            }
            else -> {
                require(folder == null) { "Unexpected positional arg: $a" }
                folder = File(a)
            }
        }
        i++
    }
    requireNotNull(folder) { "Missing folder argument. Try --help." }
    require(folder.isDirectory) { "Not a directory: ${folder.path}" }
    require(concurrency in 1..64) { "--concurrency must be 1..64" }
    return Args(folder, apply, concurrency)
}

private fun printUsage() {
    System.err.println(
        """
        Usage: scan-and-rename <folder> [--apply] [--concurrency N]

          <folder>          Directory to walk recursively
          --apply           Actually write tags and rename. Default is dry-run.
          --concurrency N   Parallel recognize calls (default 4, max 64)

        AUDD_API_TOKEN must be set in the environment.
        """.trimIndent(),
    )
}

private fun sanitizeForFilename(text: String): String {
    val cleaned = ILLEGAL_FILENAME_CHARS.replace(text, "_").trim()
    return if (cleaned.length <= MAX_FILENAME_BASE) cleaned else cleaned.substring(0, MAX_FILENAME_BASE).trim()
}

private fun yearFromReleaseDate(releaseDate: String?): String? {
    if (releaseDate.isNullOrBlank()) return null
    val match = Regex("""^\d{4}""").find(releaseDate) ?: return null
    return match.value
}

private fun audioExtension(file: File): String? {
    val ext = file.extension.lowercase()
    return if (ext in AUDIO_EXTENSIONS) ext else null
}

private fun writeTags(file: File, result: RecognitionResult) {
    val af = AudioFileIO.read(file)
    val tag = af.tagOrCreateAndSetDefault
    result.artist?.let { tag.setField(FieldKey.ARTIST, it) }
    result.title?.let { tag.setField(FieldKey.TITLE, it) }
    result.album?.let { tag.setField(FieldKey.ALBUM, it) }
    yearFromReleaseDate(result.releaseDate)?.let { tag.setField(FieldKey.YEAR, it) }
    af.commit()
}

private data class ProcessOutcome(
    val matched: Boolean,
    val tagsWritten: Boolean,
    val renamed: Boolean,
    val skippedReason: String?,
)

private suspend fun processOne(
    audd: AudD,
    file: File,
    apply: Boolean,
): ProcessOutcome = withContext(Dispatchers.IO) {
    val ext = audioExtension(file) ?: return@withContext ProcessOutcome(false, false, false, "unsupported extension")

    val result = audd.recognize(Source.FilePath(file))
    if (result == null) {
        println("[no match] ${file.name}")
        return@withContext ProcessOutcome(false, false, false, null)
    }
    val artist = result.artist?.trim().orEmpty()
    val title = result.title?.trim().orEmpty()
    if (artist.isEmpty() || title.isEmpty()) {
        println("[partial] ${file.name} -> artist='$artist' title='$title' (need both for rename)")
        return@withContext ProcessOutcome(true, false, false, "missing artist/title")
    }

    val safeArtist = sanitizeForFilename(artist)
    val safeTitle = sanitizeForFilename(title)
    val targetName = "$safeArtist - $safeTitle.$ext"
    val target = File(file.parentFile, targetName)
    val sameTarget = target.canonicalPath == file.canonicalPath

    if (!apply) {
        val plan = if (sameTarget) "name unchanged" else "rename -> $targetName"
        println("[dry-run] ${file.name} -> $artist — $title  ($plan)")
        return@withContext ProcessOutcome(true, false, false, null)
    }

    runCatching { writeTags(file, result) }
        .onFailure { exc ->
            println("[tag-error] ${file.name}: ${exc.message}")
            return@withContext ProcessOutcome(true, false, false, "tag write failed")
        }

    if (sameTarget) {
        println("[ok] ${file.name} (tags written, name unchanged)")
        return@withContext ProcessOutcome(true, true, false, null)
    }
    if (target.exists()) {
        println("[skip-collision] ${file.name} -> $targetName already exists")
        return@withContext ProcessOutcome(true, true, false, "rename target exists")
    }
    val renamed = file.renameTo(target)
    if (!renamed) {
        println("[rename-failed] ${file.name} -> $targetName")
        return@withContext ProcessOutcome(true, true, false, "rename failed")
    }
    println("[ok] ${file.name} -> $targetName")
    ProcessOutcome(true, true, true, null)
}

fun main(argv: Array<String>) = runBlocking {
    // jaudiotagger logs a lot at INFO; quiet it.
    Logger.getLogger("org.jaudiotagger").level = Level.WARNING

    val args = parseArgs(argv)
    if (!args.apply) {
        println("Dry-run mode (default). No tags written, no files renamed. Pass --apply to commit changes.")
    } else {
        println("APPLY mode: tags will be written and files renamed in place.")
    }

    val files = args.folder.walkTopDown()
        .filter { it.isFile && audioExtension(it) != null }
        .toList()
    if (files.isEmpty()) {
        println("No audio files found under ${args.folder.path}")
        return@runBlocking
    }
    println("Scanning ${files.size} audio file(s) under ${args.folder.path} (concurrency=${args.concurrency})")

    val matched = AtomicInteger()
    val tagged = AtomicInteger()
    val renamed = AtomicInteger()

    AudD(apiToken = null).use { audd ->
        val sem = Semaphore(args.concurrency)
        coroutineScope {
            files.map { f ->
                async {
                    sem.withPermit {
                        try {
                            val out = processOne(audd, f, args.apply)
                            if (out.matched) matched.incrementAndGet()
                            if (out.tagsWritten) tagged.incrementAndGet()
                            if (out.renamed) renamed.incrementAndGet()
                        } catch (exc: Throwable) {
                            println("[error] ${f.name}: ${exc.message}")
                        }
                    }
                }
            }.awaitAll()
        }
    }

    println()
    println("Summary:")
    println("  scanned : ${files.size}")
    println("  matched : ${matched.get()}")
    if (args.apply) {
        println("  tagged  : ${tagged.get()}")
        println("  renamed : ${renamed.get()}")
    } else {
        println("  (dry-run — pass --apply to write tags and rename)")
    }
}
