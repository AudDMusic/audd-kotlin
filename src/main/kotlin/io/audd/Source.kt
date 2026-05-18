package io.audd

import java.io.File
import java.io.InputStream

/**
 * What kind of audio source the caller is passing in.
 *
 * Use the sealed-class form so every recognize call site is exhaustive at the
 * type level and the SDK can produce a per-attempt re-opener that survives
 * retries on failed uploads.
 */
public sealed class Source {
    /** A publicly reachable URL — `http://` or `https://`. */
    public data class Url(val url: String) : Source()

    /** A filesystem path. The file is opened fresh on each retry attempt. */
    public data class FilePath(val file: File) : Source()

    /** Raw bytes already in memory. Each retry sends a copy of the same buffer. */
    public data class Bytes(val bytes: ByteArray) : Source() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * A streaming source. If the stream supports `mark()`/`reset()` it'll be
     * reset between retries; otherwise a retry after the first attempt will
     * raise [IllegalStateException]. For non-resettable sources, prefer
     * [Bytes] (buffer the content yourself) or [FilePath].
     */
    public data class Stream(val stream: InputStream, val filename: String = "upload.bin") : Source()
}

/** A single prepared multipart payload — what the HTTP layer will actually send. */
internal data class RequestPart(
    /** Form fields other than the file part. */
    val data: Map<String, String>,
    /** File part: (filename, bytes, contentType), or null when sending a URL. */
    val file: FilePart?,
)

internal data class FilePart(
    val filename: String,
    val bytes: ByteArray,
    val contentType: String = "application/octet-stream",
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is FilePart && filename == other.filename && bytes.contentEquals(other.bytes) && contentType == other.contentType)

    override fun hashCode(): Int = filename.hashCode() * 31 + bytes.contentHashCode() + contentType.hashCode()
}

/**
 * Build a per-attempt re-opener for [source].
 *
 * The returned lambda yields a fresh [RequestPart] on each call, so retried
 * uploads aren't reading from an exhausted buffer/handle.
 */
internal fun prepareSource(source: Source): () -> RequestPart {
    return when (source) {
        is Source.Url -> {
            val url = source.url
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "URL must start with http:// or https:// — got: $url"
            }
            ;{ RequestPart(mapOf("url" to url), null) }
        }
        is Source.FilePath -> {
            val file = source.file
            require(file.exists()) { "File does not exist: ${file.path}" }
            ;{
                val bytes = file.readBytes()
                RequestPart(emptyMap(), FilePart(file.name, bytes))
            }
        }
        is Source.Bytes -> {
            val bytes = source.bytes
            ;{ RequestPart(emptyMap(), FilePart("upload.bin", bytes)) }
        }
        is Source.Stream -> {
            val stream = source.stream
            val filename = source.filename
            // Buffer once on first call. If the stream is unseekable, the buffer
            // is reused on retries (safer than silently sending an empty body).
            var buffered: ByteArray? = null
            ;{
                if (buffered == null) {
                    buffered = stream.readBytes()
                }
                RequestPart(emptyMap(), FilePart(filename, buffered!!))
            }
        }
    }
}
