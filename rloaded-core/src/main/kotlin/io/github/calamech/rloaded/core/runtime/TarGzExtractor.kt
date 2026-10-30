package io.github.calamech.rloaded.core.runtime

import io.github.calamech.rloaded.protocol.Outcome
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream

/** Why an archive could not be extracted. */
public sealed interface ExtractionError {
    /** An entry would land outside the target directory, or has an unsupported type. Nothing is trusted after that. */
    public data class Unsafe(
        val entry: String,
        val reason: String,
    ) : ExtractionError

    /** The archive is damaged or the disk refused a write. */
    public data class Failed(
        val reason: String,
    ) : ExtractionError
}

/**
 * Extracts `.tar.gz` archives, keeping symbolic links and executable bits. This is the only file
 * that touches Commons Compress. Every entry, and every link target, must stay inside the target.
 */
internal object TarGzExtractor {
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    fun extract(
        archive: Path,
        target: Path,
    ): Outcome<Unit, ExtractionError> =
        try {
            TarArchiveInputStream(GZIPInputStream(archive.inputStream().buffered())).use { tar ->
                generateSequence { tar.nextEntry }
                    .map { entry -> write(tar, entry, target) }
                    .firstOrNull { it is Outcome.Err } ?: Outcome.Ok(Unit)
            }
        } catch (e: IOException) {
            Outcome.Err(ExtractionError.Failed(e.message ?: e.javaClass.simpleName))
        }

    private fun write(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        root: Path,
    ): Outcome<Unit, ExtractionError> {
        val path = root.resolve(entry.name).normalize()
        val linkTarget = if (entry.isSymbolicLink) path.parent.resolve(entry.linkName).normalize() else null
        val hardLinkSource = if (entry.isLink) root.resolve(entry.linkName).normalize() else null
        val escapes = listOfNotNull(path, linkTarget, hardLinkSource).any { !it.startsWith(root) }
        return when {
            Path.of(entry.name).isAbsolute || escapes -> {
                Outcome.Err(ExtractionError.Unsafe(entry.name, "points outside the archive"))
            }

            entry.isDirectory -> {
                path.createDirectories()
                Outcome.Ok(Unit)
            }

            linkTarget != null -> {
                path.parent.createDirectories()
                Files.createSymbolicLink(path, Path.of(entry.linkName))
                Outcome.Ok(Unit)
            }

            hardLinkSource != null -> {
                path.parent.createDirectories()
                Files.copy(hardLinkSource, path)
                Outcome.Ok(Unit)
            }

            entry.isFile -> {
                path.parent.createDirectories()
                Files.copy(tar, path, StandardCopyOption.REPLACE_EXISTING)
                if (posix) Files.setPosixFilePermissions(path, permissions(entry.mode))
                Outcome.Ok(Unit)
            }

            else -> {
                Outcome.Err(ExtractionError.Unsafe(entry.name, "unsupported entry type"))
            }
        }
    }

    private fun permissions(mode: Int): Set<PosixFilePermission> =
        PosixFilePermission.entries.filterIndexed { index, _ -> mode and (OWNER_READ shr index) != 0 }.toSet()

    // PosixFilePermission lists owner, group and others read/write/execute in the same order as the mode bits.
    private const val OWNER_READ = 0b100_000_000
}
