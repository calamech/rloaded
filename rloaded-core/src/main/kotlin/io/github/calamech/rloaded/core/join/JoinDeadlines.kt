package io.github.calamech.rloaded.core.join

import io.github.calamech.rloaded.protocol.Outcome
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.function.LongSupplier

/**
 * How long the server waits for a joining client: [timeout] after the install started, then
 * [timeout] after each progress message. Joins are keyed by [K], the game's connection. Safe to
 * call from the network and server threads.
 */
public class JoinDeadlines<K : Any>(
    private val timeout: Duration,
    /** Current time in nanoseconds, such as `System::nanoTime`. */
    private val nanoTime: LongSupplier,
) {
    private val deadlines = ConcurrentHashMap<K, Long>()

    /** Starts waiting for [join]. */
    public fun start(join: K) {
        deadlines[join] = deadline()
    }

    /** Waits [timeout] again for [join], if the server still waits for it. */
    public fun progress(join: K) {
        deadlines.computeIfPresent(join) { _, _ -> deadline() }
    }

    /** Stops waiting for [join]: it ended, or the player left. */
    public fun end(join: K) {
        deadlines.remove(join)
    }

    /** Removes and returns the joins whose time ran out. */
    public fun expired(): List<K> {
        val now = nanoTime.asLong
        // Compared by difference: nanoTime may be negative and only differences are meaningful.
        return deadlines.entries
            .filter { (_, deadline) -> now - deadline >= 0 }
            .mapNotNull { (join, deadline) -> join.takeIf { deadlines.remove(join, deadline) } }
    }

    private fun deadline(): Long = nanoTime.asLong + timeout.toNanos()

    /** The setting that decides [timeout], passed to the game as a system property. */
    public companion object {
        /** System property with the timeout in seconds; the bootstrap sets it from `rloaded-server.toml`. */
        public const val TIMEOUT_PROPERTY: String = "rloaded.client-timeout-seconds"

        /** Timeouts allowed, in seconds. */
        public val TIMEOUT_SECONDS: IntRange = 10..3600

        /** The timeout of a new server, in seconds. */
        public const val DEFAULT_TIMEOUT_SECONDS: Int = 120

        /** Reads the value of [TIMEOUT_PROPERTY]; a missing, unreadable or out of range value is refused. */
        public fun timeout(property: String?): Outcome<Duration, String> {
            val seconds = property?.toIntOrNull()
            return when {
                property == null -> {
                    Outcome.Err("$TIMEOUT_PROPERTY is not set; start the server with rloaded-server.jar")
                }

                seconds == null || seconds !in TIMEOUT_SECONDS -> {
                    Outcome.Err("$TIMEOUT_PROPERTY must be between ${TIMEOUT_SECONDS.first} and ${TIMEOUT_SECONDS.last}, not $property")
                }

                else -> {
                    Outcome.Ok(Duration.ofSeconds(seconds.toLong()))
                }
            }
        }
    }
}
