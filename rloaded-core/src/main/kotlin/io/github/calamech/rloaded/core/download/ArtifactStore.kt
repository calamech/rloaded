package io.github.calamech.rloaded.core.download

import io.github.calamech.rloaded.core.Outcome
import io.github.calamech.rloaded.core.flatMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A file to download, and how to recognize it once downloaded. */
public data class Artifact(
    /** Where to download the file from. */
    val url: String,
    /** The hash the file must have. Its value also names the file in the store. */
    val checksum: Checksum,
    /** The size the file must have, when the source publishes it. */
    val size: Long?,
)

/** How many times, and how patiently, a retryable download is tried again. */
public data class RetryPolicy(
    /** Attempts in total, the first one included. */
    val maxAttempts: Int = 3,
    /** Wait before the second attempt; doubled after each failure. */
    val initialBackoff: Duration = 500.milliseconds,
    /** Longest wait between two attempts. */
    val maxBackoff: Duration = 5.seconds,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }
}

/**
 * Content-addressed storage of downloaded files: a file lives at `root/ab/abcdef…`, named by its
 * hash, so a file is downloaded once and found again without any index.
 *
 * A file is written to a temporary name, checked, then moved into place atomically, so a file
 * present under its final name has already been verified.
 */
public class ArtifactStore(
    private val root: Path,
    private val http: Http,
    private val retry: RetryPolicy = RetryPolicy(),
    /** Refuses downloads without a published size that grow larger than this. */
    private val maxUnsizedBytes: Long = DEFAULT_MAX_UNSIZED_BYTES,
) {
    /** Where the file with [checksum] lives, downloaded or not. [suffix] is appended to its name. */
    public fun pathOf(
        checksum: Checksum,
        suffix: String = "",
    ): Path = root.resolve(checksum.hex.take(2)).resolve(checksum.hex + suffix)

    /** Returns the stored file for [artifact], downloading and verifying it first if needed. */
    public suspend fun obtain(
        artifact: Artifact,
        suffix: String = "",
    ): Outcome<Path, DownloadError> {
        val target = pathOf(artifact.checksum, suffix)
        if (isPresent(target, artifact)) return Outcome.Ok(target)
        var outcome = downloadOnce(artifact, target)
        var attempt = 1
        var backoff = retry.initialBackoff
        while (outcome is Outcome.Err && outcome.error.retryable && attempt < retry.maxAttempts) {
            delay(backoff)
            backoff = minOf(backoff * 2, retry.maxBackoff)
            attempt++
            outcome = downloadOnce(artifact, target)
        }
        return outcome
    }

    private suspend fun isPresent(
        target: Path,
        artifact: Artifact,
    ): Boolean = withContext(Dispatchers.IO) { target.exists() && (artifact.size == null || target.fileSize() == artifact.size) }

    private suspend fun downloadOnce(
        artifact: Artifact,
        target: Path,
    ): Outcome<Path, DownloadError> =
        createPartFile(target).flatMap { part ->
            try {
                http
                    .download(artifact.url, part, artifact.size ?: maxUnsizedBytes)
                    .flatMap { withContext(Dispatchers.IO) { verifyAndMove(artifact, part, target) } }
            } finally {
                withContext(Dispatchers.IO) { part.deleteIfExists() }
            }
        }

    private suspend fun createPartFile(target: Path): Outcome<Path, DownloadError> =
        withContext(Dispatchers.IO) {
            storageAction(target) {
                target.parent.createDirectories()
                Files.createTempFile(target.parent, target.fileName.toString(), ".part")
            }
        }

    private fun verifyAndMove(
        artifact: Artifact,
        part: Path,
        target: Path,
    ): Outcome<Path, DownloadError> {
        val size = part.fileSize()
        val actual = if (artifact.size == null || size == artifact.size) Checksum.of(artifact.checksum.algorithm, part) else null
        return when {
            actual == null -> Outcome.Err(DownloadError.SizeMismatch(artifact.url, artifact.size ?: size, size))
            actual != artifact.checksum -> Outcome.Err(DownloadError.ChecksumMismatch(artifact.url, artifact.checksum, actual))
            else -> storageAction(target) { Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    private fun storageAction(
        path: Path,
        action: () -> Path,
    ): Outcome<Path, DownloadError> =
        try {
            Outcome.Ok(action())
        } catch (e: IOException) {
            Outcome.Err(DownloadError.Storage(path, e.message ?: e.javaClass.simpleName))
        }

    private companion object {
        const val DEFAULT_MAX_UNSIZED_BYTES = 512L * 1024 * 1024
    }
}
