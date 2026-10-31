package io.github.calamech.rloaded.core.trust

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

// The files hold keys and trust decisions: they are readable by their owner only on file systems
// with POSIX permissions. Files.createTempFile already defaults to that; it is stated here so it does
// not depend on the default.

/**
 * Replaces files in one step: the bytes go to a temporary file next to the target, flushed to disk,
 * then renamed over it, so a crash leaves the old file or the new one, never half of one.
 */
public object AtomicFiles {
    /** Replaces [target] with [bytes], creating its directory; throws the [IOException] that stopped it. */
    public fun write(
        target: Path,
        bytes: ByteArray,
    ) {
        val directory = target.toAbsolutePath().parent
        Files.createDirectories(directory)
        val posix = "posix" in directory.fileSystem.supportedFileAttributeViews()
        val temporary =
            if (posix) {
                Files.createTempFile(
                    directory,
                    ".tmp-",
                    "",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                )
            } else {
                Files.createTempFile(directory, ".tmp-", "")
            }
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                channel.write(ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            Files.deleteIfExists(temporary)
            throw e
        }
    }
}
