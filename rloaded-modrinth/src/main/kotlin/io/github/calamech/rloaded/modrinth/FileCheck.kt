package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome

/**
 * The file [entry] names in this version, once Modrinth's record agrees with the entry on the
 * version, the project, the file name, the size and the SHA-512. A server can only send entries;
 * the file and its URL come from Modrinth's record.
 */
public fun ModrinthVersion.fileFor(entry: ModEntry): Outcome<ModrinthFile, FileMismatch> {
    val file = files.find { it.fileName == entry.fileName }
    return when {
        id != entry.versionId -> Outcome.Err(FileMismatch.OtherVersion(entry.versionId, id))
        projectId != entry.projectId -> Outcome.Err(FileMismatch.OtherProject(entry.projectId, projectId))
        file == null -> Outcome.Err(FileMismatch.NoSuchFile(id, entry.fileName))
        file.size != entry.size -> Outcome.Err(FileMismatch.OtherSize(entry.size, file.size))
        file.sha512 != entry.sha512 -> Outcome.Err(FileMismatch.OtherHash(entry.sha512, file.sha512))
        else -> Outcome.Ok(file)
    }
}

/** How a manifest entry disagrees with Modrinth's record. Each one refuses the entry. */
public sealed interface FileMismatch {
    /** The record is of another version than the entry names. */
    public data class OtherVersion(
        val expected: String,
        val actual: String,
    ) : FileMismatch

    /** The version belongs to another project than the entry says. */
    public data class OtherProject(
        val expected: String,
        val actual: String,
    ) : FileMismatch

    /** The version has no file with the entry's name. */
    public data class NoSuchFile(
        val versionId: String,
        val fileName: String,
    ) : FileMismatch

    /** Modrinth records another size for the file. */
    public data class OtherSize(
        val expected: Long,
        val actual: Long,
    ) : FileMismatch

    /** Modrinth records another SHA-512 for the file. */
    public data class OtherHash(
        val expected: Checksum,
        val actual: Checksum,
    ) : FileMismatch
}
