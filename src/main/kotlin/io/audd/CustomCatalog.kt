package io.audd

/**
 * The custom-catalog endpoint adds a song to your **private fingerprint database**
 * so AudD's recognition can identify your own audio for your account only.
 *
 * **This is NOT how you submit audio for music recognition.** For recognition,
 * call [AudD.recognize] (or [AudD.recognizeEnterprise] for files longer than 25
 * seconds). Custom-catalog upload requires special access — contact api@audd.io.
 */
public class CustomCatalog internal constructor(
    private val http: AudDHttp,
    private val mutatingPolicy: RetryPolicy,
) {
    private val uploadUrl = "https://api.audd.io/upload/"

    /**
     * Add (or re-fingerprint) a song under [audioId] in your private catalog.
     *
     * Calling this again with the same `audioId` overwrites that slot. There is
     * no public list/delete endpoint; track `audioId` ↔ song mappings on your side.
     *
     * **NOT for music recognition.** See class docs.
     */
    public suspend fun add(audioId: Long, source: Source) {
        val reopen = prepareSource(source)
        val resp = retry(mutatingPolicy, shouldRetryByResponse = { shouldRetryResponse(it.httpStatus, RetryClass.MUTATING) }) {
            val part = reopen()
            try {
                http.postForm(uploadUrl, part.data + ("audio_id" to audioId.toString()), part.file)
            } catch (exc: Throwable) {
                if (shouldRetryException(exc, RetryClass.MUTATING)) throw exc
                throw AudDConnectionException(exc.message ?: exc.toString(), original = exc)
            }
        }
        decodeSuccess(resp.httpStatus, resp.body, resp.requestId, customCatalogContext = true)
    }
}
