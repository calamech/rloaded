package io.github.calamech.rloaded.core.runtime

import io.github.calamech.rloaded.core.download.Artifact
import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.mojang.OperatingSystem
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.mapError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/** Why the JetBrains Runtime could not be installed. */
public sealed interface InstallError {
    /** The archive could not be downloaded or verified. */
    public data class Download(
        val error: DownloadError,
    ) : InstallError

    /** The archive could not be extracted safely. */
    public data class Extraction(
        val error: ExtractionError,
    ) : InstallError

    /** The archive does not contain a Java executable where a JetBrains Runtime has one. */
    public data class NoJavaExecutable(
        val archive: String,
    ) : InstallError
}

/**
 * Installs a JetBrains Runtime archive into `runtimesDir/jbr-<version>` and finds its Java
 * executable. An install is extracted next to its final place and moved there in one step, so a
 * runtime directory that exists is complete.
 */
public class JetBrainsRuntimeInstaller(
    private val store: ArtifactStore,
    private val runtimesDir: Path,
    private val os: OperatingSystem,
) {
    /** Returns the Java executable of runtime [version], downloading and extracting [archive] first if needed. */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    public suspend fun install(
        archive: Artifact,
        version: String,
    ): Outcome<Path, InstallError> {
        val home = runtimesDir.resolve("jbr-$version")
        val installed = withContext(Dispatchers.IO) { javaExecutable(home) }
        if (installed != null) return Outcome.Ok(installed)
        return store
            .obtain(archive, ".tar.gz")
            .mapError { InstallError.Download(it) }
            .flatMap { file ->
                withContext(Dispatchers.IO) {
                    val staging = stagingDirectory(home)
                    try {
                        extractAndMove(file, staging, home, archive.url)
                    } finally {
                        if (staging.exists()) staging.deleteRecursively()
                    }
                }
            }
    }

    private fun stagingDirectory(home: Path): Path {
        runtimesDir.createDirectories()
        return Files.createTempDirectory(runtimesDir, ".${home.fileName}-")
    }

    private fun extractAndMove(
        archive: Path,
        staging: Path,
        home: Path,
        url: String,
    ): Outcome<Path, InstallError> =
        TarGzExtractor
            .extract(archive, staging)
            .mapError<Unit, ExtractionError, InstallError> { InstallError.Extraction(it) }
            .flatMap {
                if (javaExecutable(staging) == null) {
                    Outcome.Err(InstallError.NoJavaExecutable(url))
                } else {
                    move(staging, home)
                }
            }

    private fun move(
        staging: Path,
        home: Path,
    ): Outcome<Path, InstallError> =
        try {
            Files.move(staging, home, StandardCopyOption.ATOMIC_MOVE)
            Outcome.Ok(checkNotNull(javaExecutable(home)))
        } catch (e: IOException) {
            Outcome.Err(InstallError.Extraction(ExtractionError.Failed("cannot move the runtime into place: ${e.message}")))
        }

    /** Finds `bin/java` under the archive's top directory, inside `Contents/Home` on macOS. */
    private fun javaExecutable(home: Path): Path? {
        if (!home.isDirectory()) return null
        val executable = if (os == OperatingSystem.WINDOWS) "java.exe" else "java"
        val relative = if (os == OperatingSystem.MACOS) "Contents/Home/bin/$executable" else "bin/$executable"
        return home
            .listDirectoryEntries()
            .filter { it.isDirectory() }
            .map { it.resolve(relative) }
            .firstOrNull { it.exists() }
    }
}
