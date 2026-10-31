package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.core.trust.AtomicFiles
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The compatibility database a server goes by: the one its runtime mod ships, with the versions
 * this server saw fail on players' games, which it keeps in [file]. The file has the format of
 * `compat/compat.json` and lists versions only. The server is its only writer while it runs.
 */
public class LocalCompat private constructor(
    /** Where the server keeps what it recorded. */
    public val file: Path,
    private val bundled: CompatDatabase,
    @Volatile private var recorded: Map<String, TestedVersion>,
) {
    /** The shipped database, with each recorded version in place of what it says about that version. */
    public fun current(): CompatDatabase = bundled.copy(versions = bundled.versions + recorded)

    /**
     * Records [versionId] as broken, found with Rloaded [rloaded], with [note]. Returns false when it
     * was already recorded as broken, and the reason when the file cannot be written; the version is
     * then not recorded.
     */
    @Synchronized
    public fun recordBroken(
        versionId: String,
        rloaded: String,
        note: String,
    ): Outcome<Boolean, String> {
        if (recorded[versionId]?.status == CompatStatus.BROKEN) return Outcome.Ok(false)
        val updated = recorded + (versionId to TestedVersion(CompatStatus.BROKEN, rloaded, note))
        return try {
            AtomicFiles.write(file, CompatDatabase.versionsOnly(updated).encodeToByteArray())
            recorded = updated
            Outcome.Ok(true)
        } catch (e: IOException) {
            Outcome.Err("$file cannot be written: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Opening the file. */
    public companion object {
        /**
         * Reads what the server recorded in [file], if it exists, on top of [bundled]. A file that
         * cannot be read, or that refuses categories or projects, is refused with the reason.
         */
        public fun open(
            file: Path,
            bundled: CompatDatabase,
        ): Outcome<LocalCompat, String> {
            if (!Files.exists(file)) return Outcome.Ok(LocalCompat(file, bundled, emptyMap()))
            return read(file).flatMap { CompatDatabase.parse(it) }.flatMap { local ->
                if (local.deniedCategories.isEmpty() && local.deniedProjects.isEmpty()) {
                    Outcome.Ok(LocalCompat(file, bundled, local.versions))
                } else {
                    Outcome.Err("$file lists categories or projects; it may list versions only")
                }
            }
        }

        private fun read(file: Path): Outcome<String, String> =
            try {
                Outcome.Ok(file.readText())
            } catch (e: IOException) {
                Outcome.Err("$file cannot be read: ${e.message ?: e.javaClass.simpleName}")
            }
    }
}
