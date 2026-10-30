package io.github.calamech.rloaded.core.lockfile

import io.github.calamech.rloaded.core.trust.AtomicFiles
import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/** The mods a server or a singleplayer world runs, as its `rloaded/lockfile.json` records them. */
public data class Lockfile(
    val minecraft: String,
    val rloaded: String,
    val baseSet: List<BaseSetEntry>,
    val mods: List<LockedMod>,
    /** Projects removed at the next restart. */
    val pendingRemovals: List<String>,
) {
    /**
     * SHA-256 of the base set and the mods, written as compact JSON with every field in declaration
     * order. A client compares it with the last lockfile it saw from the server.
     */
    public fun hash(): Checksum {
        val bytes = LockfileJson.canonical(this).encodeToByteArray()
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        return when (val parsed = Checksum.parse(HashAlgorithm.SHA256, hex)) {
            is Outcome.Ok -> parsed.value
            is Outcome.Err -> error("SHA-256 produced ${hex.length} hexadecimal digits")
        }
    }

    /**
     * Writes this lockfile to [path], replacing it in one step: a crash leaves the old file or the
     * new one. Fails with the reason when the file cannot be written.
     */
    public fun write(path: Path): Outcome<Unit, LockfileError> =
        try {
            AtomicFiles.write(path, LockfileJson.file(this).encodeToByteArray())
            Outcome.Ok(Unit)
        } catch (e: IOException) {
            Outcome.Err(LockfileError.Unwritable(path, e.message ?: e.javaClass.simpleName))
        }

    /** Reading lockfiles. */
    public companion object {
        /** The only schema this version reads. */
        public const val SCHEMA: Int = 1

        /** Largest lockfile read, far above a thousand mods. */
        public const val MAX_BYTES: Long = 1024L * 1024

        /** Reads [text]; an unknown field, a missing one or an invalid value refuses the whole file. */
        public fun parse(text: String): Outcome<Lockfile, LockfileError> = LockfileJson.parse(text)

        /** Reads the lockfile at [path]. */
        public fun read(path: Path): Outcome<Lockfile, LockfileError> =
            try {
                if (Files.size(path) > MAX_BYTES) {
                    Outcome.Err(LockfileError.TooLarge(path, MAX_BYTES))
                } else {
                    Outcome.Ok(Files.readString(path))
                }
            } catch (e: IOException) {
                Outcome.Err(LockfileError.Unreadable(path, e.message ?: e.javaClass.simpleName))
            }.flatMap { parse(it) }
    }
}

/** A mod of the base set every Rloaded game starts with. */
public data class BaseSetEntry(
    val projectId: String,
    val versionId: String,
    val sha512: Checksum,
)

/** A mod an admin added, with when and through which surface. */
public data class LockedMod(
    val entry: ModEntry,
    val addedAt: Instant,
    val addedBy: String,
)

/** Why a lockfile cannot be used. Each one stops the server before players join. */
public sealed interface LockfileError {
    /** The file cannot be read. */
    public data class Unreadable(
        val path: Path,
        val reason: String,
    ) : LockfileError

    /** The file cannot be written. */
    public data class Unwritable(
        val path: Path,
        val reason: String,
    ) : LockfileError

    /** The file is larger than [Lockfile.MAX_BYTES]. */
    public data class TooLarge(
        val path: Path,
        val limit: Long,
    ) : LockfileError

    /** The file was written by another version of Rloaded, with another schema. */
    public data class UnsupportedSchema(
        val schema: Int,
    ) : LockfileError

    /** The file does not have the expected shape, or a value is invalid. */
    public data class Malformed(
        val reason: String,
    ) : LockfileError
}

/** The mods of [this] as manifest entries, in lockfile order. */
public fun Lockfile.entries(): List<ModEntry> = mods.map { it.entry }
