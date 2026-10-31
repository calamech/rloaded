package io.github.calamech.rloaded.core.runtime

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Phaser
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HandoffTest {
    private val meet = Duration.ofSeconds(2)
    private val start = Duration.ofSeconds(30)

    @Test
    fun `runs the work on the offering thread while the parked thread waits for it`() {
        lateinit var parked: CompletableFuture<Handoff.Meeting<String>>
        val handoff =
            Handoff(
                {
                    assertFalse(parked.isDone, "the parked thread waits while the work runs")
                    Thread.currentThread().name
                },
                meet,
                start,
            )
        parked = CompletableFuture.supplyAsync { handoff.park() }

        assertTrue(handoff.offer())

        assertEquals(Handoff.Meeting.Ran(Outcome.Ok(Thread.currentThread().name)), parked.get(5, TimeUnit.SECONDS))
        assertTrue(handoff.offer(), "a handoff that ran is over")
    }

    @Test
    fun `misses when the other thread does not come within the meeting timeout, on either side`() {
        val quick = Duration.ofMillis(50)

        assertEquals(Handoff.Meeting.Missed, Handoff({ "work" }, quick, start).park())
        assertFalse(Handoff({ "work" }, quick, start).offer())
    }

    @Test
    fun `gives the parked thread what the work threw`() {
        val failure = IllegalStateException("boom")
        val handoff = Handoff<String>({ throw failure }, meet, start)
        val parked = CompletableFuture.supplyAsync { handoff.park() }

        assertTrue(handoff.offer())

        assertEquals(Handoff.Meeting.Ran(Outcome.Err(failure)), parked.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `is called off past the start timeout or when asked, and not once the work ran`() {
        var now = 0L
        val late = Handoff({ "work" }, Duration.ofMillis(10), Duration.ofSeconds(1)) { now }
        now = Duration.ofSeconds(1).toNanos()
        assertEquals(Handoff.Meeting.CalledOff, late.park())
        assertTrue(late.offer(), "a called off handoff is over for the working thread too")

        val asked = Handoff({ "work" }, Duration.ofMillis(10), start)
        assertTrue(asked.callOff())
        assertEquals(Handoff.Meeting.CalledOff, asked.park())

        val ran = Handoff({ "work" }, meet, start)
        val parked = CompletableFuture.supplyAsync { ran.park() }
        ran.offer()
        assertIs<Handoff.Meeting.Ran<String>>(parked.get(5, TimeUnit.SECONDS))
        assertFalse(ran.callOff())
    }

    // Two threads that only move on together, a tick at a time, as the game's client and server do
    // in client game tests: each comes to the handoff once per tick, in any order. A thread that
    // waited for the other without coming back to the shared tick would never meet it.
    @Test
    fun `meets within the first tick whichever thread comes first, when both only move on together`() {
        repeat(RUNS) {
            val ticks = Phaser(2)
            val handoff = Handoff({ "work" }, meet, start)
            var parkerTicks = 0
            val parker =
                CompletableFuture.supplyAsync {
                    var meeting: Handoff.Meeting<String> = Handoff.Meeting.Missed
                    while (meeting == Handoff.Meeting.Missed) {
                        Thread.sleep(Random.nextLong(5))
                        meeting = handoff.park()
                        parkerTicks++
                        ticks.arriveAndAwaitAdvance()
                    }
                    ticks.arriveAndDeregister()
                    meeting
                }
            var over = false
            while (!over) {
                Thread.sleep(Random.nextLong(5))
                over = handoff.offer()
                ticks.arriveAndAwaitAdvance()
            }
            ticks.arriveAndDeregister()

            assertEquals(Handoff.Meeting.Ran(Outcome.Ok("work")), parker.get(10, TimeUnit.SECONDS))
            assertEquals(1, parkerTicks)
        }
    }

    private companion object {
        const val RUNS = 50
    }
}
