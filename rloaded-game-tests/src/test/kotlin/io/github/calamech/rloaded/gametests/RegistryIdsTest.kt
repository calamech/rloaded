package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RegistryIdsTest {
    private val added = listOf("test:a", "test:b")
    private val server = ids("block test:a" to 10, "block test:b" to 11, "state Block{test:a}[lit=true]" to 40)
    private val swapped = ids("block test:a" to 11, "block test:b" to 10, "state Block{test:a}[lit=true]" to 44)

    @Test
    fun `reads blocks and states`() {
        val text = "block minecraft:stone 1\nstate Block{minecraft:stone} 1\n\n"

        assertEquals(Outcome.Ok(ids("block minecraft:stone" to 1, "state Block{minecraft:stone}" to 1)), RegistryIds.parse(text))
    }

    @Test
    fun `refuses a line that is not a raw id`() {
        assertEquals(Outcome.Err("not a raw id line: block minecraft:stone one"), RegistryIds.parse("block minecraft:stone one\n"))
        assertEquals(Outcome.Err("not a raw id line: block minecraft:stone"), RegistryIds.parse("block minecraft:stone\n"))
        assertEquals(Outcome.Err("no raw ids"), RegistryIds.parse("\n"))
    }

    @Test
    fun `passes when a client diverged before the sync and every client agrees after it`() {
        val problems =
            registrySyncProblems(
                server,
                mapOf("client-1" to server, "client-2" to swapped),
                mapOf(
                    "client-1" to server,
                    "client-2" to server,
                ),
                added,
            )

        assertEquals(emptyList(), problems)
    }

    @Test
    fun `fails when a client still disagrees after the sync`() {
        val problems = registrySyncProblems(server, mapOf("client-2" to swapped), mapOf("client-1" to server, "client-2" to swapped), added)

        assertEquals(
            listOf(
                "client-2 disagrees with the server on 3 ids after the sync: block test:a: server 10, client 11; " +
                    "block test:b: server 11, client 10; state Block{test:a}[lit=true]: server 40, client 44",
            ),
            problems,
        )
    }

    @Test
    fun `fails when a client has an id the server lacks`() {
        val extra = ids(*(server.ids.toList() + ("block test:c" to 12)).toTypedArray())

        val problems = registrySyncProblems(server, mapOf("client-2" to swapped), mapOf("client-1" to extra), added)

        assertEquals(listOf("client-1 disagrees with the server on 1 ids after the sync: block test:c: server null, client 12"), problems)
    }

    @Test
    fun `fails when no client diverged, as agreement then proves nothing`() {
        val problems = registrySyncProblems(server, mapOf("client-1" to server), mapOf("client-1" to server), added)

        assertEquals(listOf("no client gave the added blocks other raw ids before the sync"), problems)
    }

    @Test
    fun `fails when the server lacks an added block`() {
        val problems = registrySyncProblems(server, mapOf("client-2" to swapped), mapOf("client-1" to server), added + "test:c")

        assertEquals(listOf("the server has no block test:c"), problems)
    }

    private fun ids(vararg entries: Pair<String, Int>) = RegistryIds(entries.toMap())
}
