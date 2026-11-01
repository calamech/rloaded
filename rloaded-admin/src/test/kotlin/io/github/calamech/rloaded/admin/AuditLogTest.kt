package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

class AuditLogTest {
    @TempDir
    lateinit var directory: Path

    private val file get() = directory.resolve("rloaded/audit.log")

    @Test
    fun `gives each add a version 7 id that starts with the time`() {
        val at = Instant.parse("2026-11-01T03:00:00Z")
        val log = AuditLog(file, Clock.fixed(at, ZoneOffset.UTC)) { throw AssertionError("unexpected failure $it") }

        val ids = List(3) { (log.requested(AddActor.Console, AuditAction.ADD, "menu", null) as Outcome.Ok).value.id }

        assertEquals(listOf(7, 7, 7), ids.map { it.version() })
        assertEquals(listOf(2, 2, 2), ids.map { it.variant() })
        assertEquals(setOf(at.toEpochMilli()), ids.map { it.mostSignificantBits ushr 16 }.toSet())
        assertEquals(3, ids.toSet().size)
    }

    @Test
    fun `names the player of a singleplayer world apart from a server's`() {
        val log = AuditLog(file, Clock.systemUTC()) { throw AssertionError("unexpected failure $it") }
        val id = UUID.fromString("0f1c2a6e-2f7e-4b2a-9d0e-3c1c4d5e6f70")

        log.requested(AddActor.WorldPlayer("Alice", id), AuditAction.ADD, "menu", null)
        log.requested(AddActor.DedicatedPlayer("Alice", id), AuditAction.ADD, "menu", null)

        assertEquals(
            listOf("\"world_player\"", "\"player\""),
            Files.readAllLines(file).map { Json.parseToJsonElement(it).jsonObject["actor"].toString() },
        )
    }
}
