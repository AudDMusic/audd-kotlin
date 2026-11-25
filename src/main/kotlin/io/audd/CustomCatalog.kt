package io.audd

/**
 * The custom-catalog endpoint adds a song to your **private fingerprint database**
 * so AudD's recognition can identify your own audio for your account only.
 *
 * **This is NOT how you submit audio for music recognition.** For recognition,
 * call [AudD.recognize] (or [AudD.recognizeEnterprise] to scan beyond the first
 * 12 seconds of a file). Custom-catalog upload requires special access — contact api@audd.io.
 */
public class CustomCatalog internal constructor(
    private val http: AudDHttp,
    private val criticalPolicy: RetryPolicy,
) {
    private val uploadUrl = "https://api.audd.io/upload/"

    /**
     * Add (or re-fingerprint) a song under [audioId] in your private catalog.
     *
     * Calling this again with the same `audioId` overwrites that slot. There is
     * no public list/delete endpoint; track `audioId` ↔ song mappings on your side.
     *
     * **NOT for music recognition.** See class docs.
     *
     * **Never retried automatically.** The upload is sent once: a failure (5xx,
     * connection error) surfaces as a clean exception, so check the error
     * before sending it again (an upload to an `audioId` that already holds a
     * song replaces it). This is independent of `AudD(maxRetries = ...)`.
     */
    public suspend fun add(audioId: Long, source: Source) {
        val reopen = prepareSource(source)
        val resp = retry(criticalPolicy, shouldRetryByResponse = { shouldRetryResponse(it.httpStatus, RetryClass.CRITICAL) }) {
            val part = reopen()
            transportAttempt(RetryClass.CRITICAL) {
                http.postForm(uploadUrl, part.data + ("audio_id" to audioId.toString()), part.file)
            }
        }
        decodeSuccess(resp.httpStatus, resp.body, resp.requestId, customCatalogContext = true)
    }
}
