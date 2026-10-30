package io.github.calamech.rloaded.core.lockfile

import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.traverse
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

// The file as written on disk. Rloaded writes it, so unknown fields are refused rather than ignored.
internal object LockfileJson {
    private val strict = Json { ignoreUnknownKeys = false }

    private val compact =
        Json {
            encodeDefaults = true
            explicitNulls = true
            prettyPrint = false
        }

    fun parse(text: String): Outcome<Lockfile, LockfileError> =
        decode(text).flatMap { file ->
            if (file.schema != Lockfile.SCHEMA) Outcome.Err(LockfileError.UnsupportedSchema(file.schema)) else toLockfile(file)
        }

    fun canonical(lockfile: Lockfile): String =
        compact.encodeToString(
            HashedContent.serializer(),
            HashedContent(
                lockfile.baseSet.map { BaseSetJson(it.projectId, it.versionId, it.sha512.hex) },
                lockfile.mods.map { mod ->
                    val entry = mod.entry
                    ModJson(
                        entry.projectId,
                        entry.versionId,
                        entry.fileName,
                        entry.sha512.hex,
                        entry.size,
                        entry.side,
                        mod.addedAt.toString(),
                        mod.addedBy,
                    )
                },
            ),
        )

    private fun decode(text: String): Outcome<FileJson, LockfileError> =
        try {
            Outcome.Ok(strict.decodeFromString(FileJson.serializer(), text))
        } catch (e: SerializationException) {
            Outcome.Err(LockfileError.Malformed(e.message ?: "invalid JSON"))
        } catch (e: IllegalArgumentException) {
            Outcome.Err(LockfileError.Malformed(e.message ?: "invalid JSON"))
        }

    private fun toLockfile(file: FileJson): Outcome<Lockfile, LockfileError> =
        file.baseSet
            .traverse { base ->
                sha512(base.sha512, base.projectId).map { BaseSetEntry(base.projectId, base.versionId, it) }
            }.flatMap { baseSet ->
                file.mods.traverse { toMod(it) }.flatMap { mods ->
                    val duplicate =
                        mods
                            .groupBy { it.entry.projectId }
                            .filterValues { it.size > 1 }
                            .keys
                            .firstOrNull()
                    if (duplicate != null) {
                        Outcome.Err(LockfileError.Malformed("project $duplicate is listed twice"))
                    } else {
                        Outcome.Ok(Lockfile(file.minecraft, file.rloaded, baseSet, mods, file.pendingRemovals))
                    }
                }
            }

    private fun toMod(mod: ModJson): Outcome<LockedMod, LockfileError> =
        sha512(mod.sha512, mod.projectId).flatMap { sha512 ->
            try {
                val entry = ModEntry(mod.projectId, mod.versionId, mod.fileName, sha512, mod.size, mod.side)
                Outcome.Ok(LockedMod(entry, Instant.parse(mod.addedAt), mod.addedBy))
            } catch (e: IllegalArgumentException) {
                Outcome.Err(LockfileError.Malformed("mod ${mod.projectId}: ${e.message}"))
            } catch (e: DateTimeParseException) {
                Outcome.Err(LockfileError.Malformed("mod ${mod.projectId}: addedAt is not an ISO-8601 instant"))
            }
        }

    private fun sha512(
        hex: String,
        projectId: String,
    ): Outcome<Checksum, LockfileError> =
        when (val parsed = Checksum.parse(HashAlgorithm.SHA512, hex)) {
            is Outcome.Ok -> parsed
            is Outcome.Err -> Outcome.Err(LockfileError.Malformed("$projectId has no valid SHA-512"))
        }

    @Serializable
    private data class FileJson(
        val schema: Int,
        val minecraft: String,
        val rloaded: String,
        val baseSet: List<BaseSetJson>,
        val mods: List<ModJson>,
        val pendingRemovals: List<String>,
    )

    @Serializable
    private data class HashedContent(
        val baseSet: List<BaseSetJson>,
        val mods: List<ModJson>,
    )

    @Serializable
    private data class BaseSetJson(
        val projectId: String,
        val versionId: String,
        val sha512: String,
    )

    @Serializable
    private data class ModJson(
        val projectId: String,
        val versionId: String,
        val fileName: String,
        val sha512: String,
        val size: Long,
        val side: Side,
        val addedAt: String,
        val addedBy: String,
    )
}
