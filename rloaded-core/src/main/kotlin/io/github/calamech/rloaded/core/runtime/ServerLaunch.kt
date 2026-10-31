package io.github.calamech.rloaded.core.runtime

import io.github.calamech.rloaded.core.trust.AtomicFiles
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * How a server is started: Fabric's server on the pinned runtime with the agent, [mods] loaded
 * after the base game, and each of [properties] passed as `-Dname=value`. The bootstrap writes it
 * next to the server, so the server can start another one the same way.
 */
@Serializable
public data class ServerLaunch(
    val java: String,
    val maxMemoryMiB: Int,
    val agent: String,
    /** Fabric Loader and its libraries, then the server jar. */
    val classpath: List<String>,
    /** Fabric Loader's server entry point. */
    val mainClass: String,
    /** The base set, the runtime mod, then the lockfile's mods. */
    val mods: List<String>,
    val properties: Map<String, String>,
) {
    /** The command line that starts the server. */
    public fun command(): List<String> =
        listOf(
            java,
            "-Xmx${maxMemoryMiB}M",
            "-XX:+AllowEnhancedClassRedefinition",
            "-javaagent:$agent",
            "-Dfabric.addMods=${mods.joinToString(File.pathSeparator)}",
        ) + properties.map { (name, value) -> "-D$name=$value" } +
            listOf("-cp", classpath.joinToString(File.pathSeparator), mainClass, "nogui")

    /** This launch, telling the server that [file] describes it. */
    public fun describedBy(file: Path): ServerLaunch = copy(properties = properties + (PROPERTY to file.toString()))

    /** Writes this launch to [path] in one step. Fails with the reason when it cannot. */
    public fun write(path: Path): Outcome<Unit, String> =
        try {
            AtomicFiles.write(path, JSON.encodeToString(serializer(), this).encodeToByteArray())
            Outcome.Ok(Unit)
        } catch (e: IOException) {
            Outcome.Err("cannot write $path: ${e.message}")
        }

    /** Reading launch descriptions. */
    public companion object {
        /** The system property that gives a server the file describing its own launch. */
        public const val PROPERTY: String = "rloaded.server-launch"

        /** Largest description read; a thousand mods with long paths fit. */
        public const val MAX_BYTES: Long = 1024L * 1024

        private val JSON = Json { ignoreUnknownKeys = false }

        /** Reads the description at [path]; an unknown field or a missing one refuses it. */
        public fun read(path: Path): Outcome<ServerLaunch, String> =
            try {
                if (Files.size(path) > MAX_BYTES) {
                    Outcome.Err("$path is larger than $MAX_BYTES bytes")
                } else {
                    Outcome.Ok(JSON.decodeFromString(serializer(), Files.readString(path)))
                }
            } catch (e: IOException) {
                Outcome.Err("cannot read $path: ${e.message}")
            } catch (e: SerializationException) {
                Outcome.Err("$path is invalid: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Outcome.Err("$path is invalid: ${e.message}")
            }
    }
}
