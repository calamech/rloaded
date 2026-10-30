package io.github.calamech.rloaded.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OutcomeTest {
    private val ok: Outcome<Int, String> = Outcome.Ok(2)
    private val err: Outcome<Int, String> = Outcome.Err("broken")

    @Test
    fun `map transforms a value and keeps an error`() {
        assertEquals(Outcome.Ok(4), ok.map { it * 2 })
        assertEquals(Outcome.Err("broken"), err.map { it * 2 })
    }

    @Test
    fun `flatMap chains on a value and keeps an error`() {
        assertEquals(Outcome.Ok("2"), ok.flatMap { Outcome.Ok(it.toString()) })
        assertEquals(Outcome.Err("refused"), ok.flatMap { Outcome.Err("refused") })
        assertEquals(Outcome.Err("broken"), err.flatMap { Outcome.Ok(it.toString()) })
    }

    @Test
    fun `mapError transforms an error and keeps a value`() {
        assertEquals(Outcome.Ok(2), ok.mapError { it.length })
        assertEquals(Outcome.Err(6), err.mapError { it.length })
    }

    @Test
    fun `traverse collects every value or stops at the first error`() {
        val parse = { text: String -> text.toIntOrNull()?.let { Outcome.Ok(it) } ?: Outcome.Err(text) }

        assertEquals(Outcome.Ok(listOf(1, 2, 3)), listOf("1", "2", "3").traverse(parse))
        assertEquals(Outcome.Err("x"), listOf("1", "x", "y").traverse(parse))
    }
}
