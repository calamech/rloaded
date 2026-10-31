package io.github.calamech.rloaded.core.runtime

import io.github.calamech.rloaded.protocol.Outcome
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs [work] on one thread while another, which must not run meanwhile, waits for it. Each thread
 * comes back to the handoff at a point of its own loop, once a tick: the waiting thread to [park],
 * the working thread to [offer]. Whichever comes first waits for the other up to the meeting
 * timeout, and on a miss goes on with its loop and comes back at its next tick. Neither ever waits
 * for the other without a bound, so the two threads meet even when one of them waits for the
 * other elsewhere. Once the work started, the parked thread waits for its end.
 *
 * Past [startTimeout] without the work started, the handoff is called off; [callOff] does it
 * earlier.
 */
public class Handoff<T>(
    private val work: () -> T,
    private val meetTimeout: Duration,
    startTimeout: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val startBy = nanoTime() + startTimeout.toNanos()
    private var stage = Stage.OPEN
    private var outcome: Outcome<T, Throwable>? = null

    private enum class Claim {
        RUN,
        OVER,
        LATER,
    }

    private enum class Stage {
        OPEN,
        PARKED,
        RUNNING,
        CLOSED,
    }

    /** The waiting thread's turn: parks until the work ran, or until the other thread does not come. */
    public fun park(): Meeting<T> =
        lock.withLock {
            if (stage == Stage.CLOSED || closedForLateness()) return finished()
            stage = Stage.PARKED
            changed.signalAll()
            awaitWhile(Stage.PARKED, meetTimeout.toNanos())
            if (stage == Stage.PARKED) {
                stage = Stage.OPEN
                return Meeting.Missed
            }
            while (stage == Stage.RUNNING) changed.await()
            finished()
        }

    /**
     * The working thread's turn: runs the work if the other thread is parked or parks soon enough.
     *
     * @return whether the handoff is over, the work done or called off; false to come back later
     */
    public fun offer(): Boolean =
        when (lock.withLock { claim() }) {
            Claim.RUN -> {
                run()
                true
            }

            Claim.OVER -> {
                true
            }

            Claim.LATER -> {
                false
            }
        }

    private fun claim(): Claim {
        if (stage == Stage.CLOSED || closedForLateness()) return Claim.OVER
        awaitWhile(Stage.OPEN, meetTimeout.toNanos())
        return when (stage) {
            Stage.PARKED -> {
                stage = Stage.RUNNING
                Claim.RUN
            }

            Stage.CLOSED -> {
                Claim.OVER
            }

            Stage.OPEN, Stage.RUNNING -> {
                Claim.LATER
            }
        }
    }

    private fun run() {
        val result =
            try {
                Outcome.Ok(work())
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                // The parked thread decides what a failure of the work means.
                Outcome.Err(e)
            }
        lock.withLock {
            outcome = result
            stage = Stage.CLOSED
            changed.signalAll()
        }
    }

    /**
     * Calls the handoff off unless the work started.
     *
     * @return whether it is called off; false when the work started or ended
     */
    public fun callOff(): Boolean =
        lock.withLock {
            if (stage == Stage.OPEN || stage == Stage.PARKED) {
                stage = Stage.CLOSED
                changed.signalAll()
            }
            stage == Stage.CLOSED && outcome == null
        }

    private fun closedForLateness(): Boolean {
        if (nanoTime() - startBy < 0) return false
        stage = Stage.CLOSED
        changed.signalAll()
        return true
    }

    private fun awaitWhile(
        current: Stage,
        nanos: Long,
    ) {
        var left = nanos
        while (stage == current && left > 0) left = changed.awaitNanos(left)
    }

    private fun finished(): Meeting<T> = outcome?.let { Meeting.Ran(it) } ?: Meeting.CalledOff

    /** What came of the waiting thread's turn. */
    public sealed interface Meeting<out T> {
        /** The work ran, with this outcome: its value, or what it threw. */
        public data class Ran<out T>(
            val outcome: Outcome<T, Throwable>,
        ) : Meeting<T>

        /** The working thread did not come within the meeting timeout; park again at the next tick. */
        public data object Missed : Meeting<Nothing>

        /** The handoff was called off before the work started. */
        public data object CalledOff : Meeting<Nothing>
    }
}
