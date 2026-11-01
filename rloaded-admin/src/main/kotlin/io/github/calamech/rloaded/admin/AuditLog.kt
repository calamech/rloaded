package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.protocol.AddProblem
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.map
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/** Why the audit log could not be written. */
public data class AuditError(
    val file: Path,
    val reason: String,
)

/**
 * Appends every add asked of a server or a world to [file], one JSON object per line: a `requested`
 * line before anything happens, then an `added` or `refused` line with the same `add` id. Lines are
 * only ever appended. A `requested` line without an outcome is an add the game stopped during.
 *
 * Writing the request is required before an add starts; an outcome that cannot be written goes to
 * [onLateFailure], since the add already happened or was refused.
 */
public class AuditLog(
    private val file: Path,
    private val clock: Clock,
    private val onLateFailure: (AuditError) -> Unit,
) {
    private val random = SecureRandom()

    /** Records that [actor] asks for [project] at [version], or its newest release when null. */
    public fun requested(
        actor: AddActor,
        project: String,
        version: String?,
    ): Outcome<AuditedAdd, AuditError> {
        val add = timeOrderedId()
        return append(
            line(add, "requested") {
                actor(actor)
                put("project", project)
                put("version", version)
            },
        ).map { AuditedAdd(this, add) }
    }

    /** Records an add a surface refused before asking for it, with the [problem] it shows. */
    public fun refused(
        actor: AddActor,
        project: String,
        version: String?,
        problem: AddProblem,
        reason: String,
    ) {
        when (val requested = requested(actor, project, version)) {
            is Outcome.Err -> onLateFailure(requested.error)
            is Outcome.Ok -> requested.value.refused(problem, reason)
        }
    }

    internal fun outcome(
        add: UUID,
        event: String,
        fields: JsonObjectBuilder.() -> Unit,
    ) {
        val written = append(line(add, event, fields))
        if (written is Outcome.Err) onLateFailure(written.error)
    }

    private fun line(
        add: UUID,
        event: String,
        fields: JsonObjectBuilder.() -> Unit,
    ): String =
        buildJsonObject {
            put("schema", SCHEMA)
            put("time", clock.instant().toString())
            put("add", add.toString())
            put("event", event)
            fields()
        }.toString() + "\n"

    @Synchronized
    private fun append(line: String): Outcome<Unit, AuditError> =
        try {
            Files.createDirectories(file.toAbsolutePath().parent)
            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                channel.write(ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8)))
                channel.force(true)
            }
            Outcome.Ok(Unit)
        } catch (e: IOException) {
            Outcome.Err(AuditError(file, e.toString()))
        }

    // A version 7 UUID (RFC 9562): milliseconds since the epoch first, so ids sort by time.
    private fun timeOrderedId(): UUID {
        val millis = clock.millis() and TIMESTAMP_MASK
        val high = (millis shl TIMESTAMP_SHIFT) or VERSION_7 or (random.nextLong() and RAND_A_MASK)
        val low = (random.nextLong() and RAND_B_MASK) or Long.MIN_VALUE
        return UUID(high, low)
    }

    private companion object {
        const val SCHEMA = 1
        const val TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL
        const val TIMESTAMP_SHIFT = 16
        const val VERSION_7 = 0x7000L
        const val RAND_A_MASK = 0x0FFFL
        const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL
    }
}

/** An add whose request is in the audit log; records its outcome once. */
public class AuditedAdd internal constructor(
    private val log: AuditLog,
    /** The id its lines share. */
    public val id: UUID,
) {
    /** Records the mods the add wrote to the lockfile. */
    public fun added(mods: List<AddedMod>) {
        log.outcome(id, "added") {
            putJsonArray("mods") {
                for (mod in mods) {
                    addJsonObject {
                        put("project", mod.entry.projectId)
                        put("version", mod.entry.versionId)
                        put("versionNumber", mod.versionNumber)
                        put("sha512", mod.entry.sha512.hex)
                    }
                }
            }
        }
    }

    /** Records why the add was refused. */
    public fun refused(
        problem: AddProblem,
        reason: String,
    ) {
        log.outcome(id, "refused") {
            put("problem", problem.name)
            put("reason", reason.take(MAX_REASON))
        }
    }

    private companion object {
        // Reasons can quote a mod's own log lines; the audit log keeps the start of them.
        const val MAX_REASON = 1_000
    }
}

private fun JsonObjectBuilder.actor(actor: AddActor) {
    when (actor) {
        AddActor.Console -> {
            put("actor", "console")
        }

        is AddActor.DedicatedPlayer -> {
            put("actor", "player")
            put("player", actor.name)
            put("playerId", actor.id.toString())
        }

        is AddActor.WorldPlayer -> {
            put("actor", "world_player")
            put("player", actor.name)
            put("playerId", actor.id.toString())
        }
    }
}
