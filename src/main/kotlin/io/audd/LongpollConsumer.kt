package io.audd

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Tokenless longpoll consumer — for browser/widget/extension use cases that
 * already know the [category] and don't carry the api_token.
 *
 * The user/server who derived the category is responsible for ensuring a
 * callback URL is set on their account (we can't preflight that without a token).
 *
 * Usage mirrors [Streams.longpoll]:
 *
 * ```kotlin
 * LongpollConsumer(category).use { consumer ->
 *     consumer.iterate().use { poll ->
 *         coroutineScope {
 *             launch { poll.matches.collect { … } }
 *             launch { poll.notifications.collect { … } }
 *             launch { poll.errors.collect { err -> throw err } }
 *         }
 *     }
 * }
 * ```
 *
 * @param maxAttempts attempts per longpoll request on network errors and
 *   HTTP 5xx, the first one included (values below 1 count as 1). See
 *   [LongpollPoll] for how the subscription runs.
 * @param backoffFactor base for the exponential backoff between those
 *   attempts, in seconds.
 */
public class LongpollConsumer(
    private val category: String,
    private val maxAttempts: Int = 3,
    private val backoffFactor: Double = 0.5,
    engine: HttpClientEngine? = null,
    httpClient: HttpClient? = null,
) : AutoCloseable {

    private val http: AudDHttp = AudDHttp(
        apiToken = "",
        timeouts = Timeouts(connectMillis = 10_000L, readMillis = 120_000L, writeMillis = 10_000L),
        engine = engine,
        httpClient = httpClient,
    )
    private val policy: RetryPolicy = RetryPolicy(RetryClass.READ, maxAttempts, backoffFactor)

    /** Parent of every subscription started by [iterate]; [close] cancels them. */
    private val polls: Job = SupervisorJob()

    /**
     * Start a long-poll subscription with the consumer's category. Returns a
     * [LongpollPoll] — same shape as [Streams.longpoll], minus the
     * `getCallbackUrl` preflight (no token to call it with).
     */
    public fun iterate(opts: LongpollOptions = LongpollOptions(skipCallbackCheck = true)): LongpollPoll {
        return LongpollPoll.start(
            scope = CoroutineScope(SupervisorJob(polls) + Dispatchers.Default),
            ownsScope = true,
            source = StreamsLongpollSource(http, policy, LONGPOLL_URL),
            category = category,
            sinceTime = opts.sinceTime,
            timeoutSeconds = opts.timeoutSeconds,
        )
    }

    /** Ends every subscription started by [iterate], then releases the HTTP transport. */
    override fun close() {
        polls.cancel()
        http.close()
    }

    public companion object {
        internal const val LONGPOLL_URL = "https://api.audd.io/longpoll/"
    }

    /** Builder DSL for configuring LongpollConsumer. */
    public class Builder(private val category: String) {
        public var maxAttempts: Int = 3
        public var backoffFactor: Double = 0.5
        public var engine: HttpClientEngine? = null
        public var httpClient: HttpClient? = null

        public fun build(): LongpollConsumer = LongpollConsumer(
            category = category,
            maxAttempts = maxAttempts,
            backoffFactor = backoffFactor,
            engine = engine,
            httpClient = httpClient,
        )
    }
}
