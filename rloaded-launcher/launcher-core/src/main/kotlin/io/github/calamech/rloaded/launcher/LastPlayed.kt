package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.core.trust.AtomicFiles
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** What the player typed the last time a game started: the window fills its fields with it. */
@Serializable
public data class LastPlayed(
    val playerName: String,
    /** The server as typed, empty for the title screen. */
    val server: String,
) {
    /** Reading and writing the file. */
    public companion object {
        /** Before the first game: nothing typed yet. */
        public val NONE: LastPlayed = LastPlayed("", "")

        private const val MAX_BYTES = 4096L
        private val JSON = Json { ignoreUnknownKeys = false }

        /**
         * Reads [file]. A missing file is [NONE]; one that cannot be read is an error, so the
         * window can start empty and the launcher log can say why.
         */
        public fun read(file: Path): Outcome<LastPlayed, String> =
            try {
                if (Files.size(file) > MAX_BYTES) {
                    Outcome.Err("$file is larger than $MAX_BYTES bytes")
                } else {
                    Outcome.Ok(JSON.decodeFromString(serializer(), Files.readString(file)))
                }
            } catch (_: NoSuchFileException) {
                Outcome.Ok(NONE)
            } catch (e: IOException) {
                Outcome.Err("$file cannot be read: ${e.message}")
            } catch (e: SerializationException) {
                Outcome.Err("$file is not what the launcher writes: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Outcome.Err("$file is not what the launcher writes: ${e.message}")
            }
    }

    /** Writes this to [file] in one step, or says why it could not. */
    public fun write(file: Path): Outcome<Unit, String> =
        try {
            AtomicFiles.write(file, JSON.encodeToString(serializer(), this).encodeToByteArray())
            Outcome.Ok(Unit)
        } catch (e: IOException) {
            Outcome.Err("$file cannot be written: ${e.message}")
        }
}
