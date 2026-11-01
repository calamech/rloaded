package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.map
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
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
import java.util.Locale
import java.util.UUID

/** Why the audit log could not be written. */
public data class AuditError(
    val file: Path,
    val reason: String,
)

/** What a request of the audit log asks for. */
public enum class AuditAction {
    /** Add a mod and what it needs. */
    ADD,

    /** Remove a mod at the next restart. */
    REMOVE,

    /** Keep a mod that was to be removed at the next restart. */
    KEEP,
}

/** What a request asks for: to [action] [project], at [version] for an add or its newest release when null. */
public data class AuditRequest(
    val action: AuditAction,
    val project: String,
    val version: String?,
)

/**
 * Appends every change of mods asked of a server or a world to [file], one JSON object per line: a
 * `requested` line before anything happens, then its outcome with the same `request` id (`added`,
 * `marked` or `kept`, or `refused`). Restarts add `removed` and `postponed` lines when they apply
 * removals. Lines are only ever appended. A `requested` line without an outcome is a request the
 * game stopped during.
 *
 * Writing the request is required before it is acted on; an outcome that cannot be written goes to
 * [onLateFailure], since the change already happened or was refused.
 */
public class AuditLog(
    private val file: Path,
    private val clock: Clock,
    private val onLateFailure: (AuditError) -> Unit,
) {
    private val random = SecureRandom()

    /** Records that [actor] asks for [request]. */
    public fun requested(
        actor: AddActor,
        request: AuditRequest,
    ): Outcome<AuditedRequest, AuditError> {
        val id = timeOrderedId()
        return append(
            line(id, "requested") {
                put("action", request.action.name.lowercase(Locale.ROOT))
                actor(actor)
                put("project", request.project)
                put("version", request.version)
            },
        ).map { AuditedRequest(this, id) }
    }

    /** Records a request a surface refused before acting on it, with the [problem] it shows. */
    public fun refused(
        actor: AddActor,
        request: AuditRequest,
        problem: Enum<*>,
        reason: String,
    ) {
        when (val requested = requested(actor, request)) {
            is Outcome.Err -> onLateFailure(requested.error)
            is Outcome.Ok -> requested.value.refused(problem, reason)
        }
    }

    /** Records that a restart removed [projects] from the lockfile, after it saved the world to [backup], if it had one. */
    public fun removed(
        projects: List<String>,
        backup: Path?,
    ) {
        outcome(timeOrderedId(), "removed") {
            put("actor", "restart")
            putJsonArray("projects") { projects.forEach { add(JsonPrimitive(it)) } }
            put("backup", backup?.fileName?.toString())
        }
    }

    /** Records that a restart kept [projects], to be removed later, for [reason]. */
    public fun postponed(
        projects: List<String>,
        reason: String,
    ) {
        outcome(timeOrderedId(), "postponed") {
            put("actor", "restart")
            putJsonArray("projects") { projects.forEach { add(JsonPrimitive(it)) } }
            put("reason", reason.take(MAX_REASON))
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
            put("request", add.toString())
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
        const val SCHEMA = 2
        const val TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL
        const val TIMESTAMP_SHIFT = 16
        const val VERSION_7 = 0x7000L
        const val RAND_A_MASK = 0x0FFFL
        const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL
    }
}

/** A request in the audit log; records its outcome once. */
public class AuditedRequest internal constructor(
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

    /** Records that the mod will be removed at the next restart. */
    public fun marked() {
        log.outcome(id, "marked") { }
    }

    /** Records that the mod will stay after all. */
    public fun kept() {
        log.outcome(id, "kept") { }
    }

    /** Records why the request was refused, with the [problem] the player sees. */
    public fun refused(
        problem: Enum<*>,
        reason: String,
    ) {
        log.outcome(id, "refused") {
            put("problem", problem.name)
            put("reason", reason.take(MAX_REASON))
        }
    }
}

// Reasons can quote a mod's own log lines; the audit log keeps the start of them.
private const val MAX_REASON = 1_000

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
