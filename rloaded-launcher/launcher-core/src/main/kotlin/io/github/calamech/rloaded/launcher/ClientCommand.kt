package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.traverse
import java.io.File
import java.nio.file.Path

/** How to start the client. */
public data class LaunchSettings(
    val profile: OfflineProfile,
    /** Options, saves and logs of this game. */
    val gameDirectory: Path,
    /** The server to join at startup, or null to open the title screen. */
    val server: ServerAddress?,
    /** The Rloaded agent jar, given to the JVM with `-javaagent`. */
    val agentJar: Path,
    /** The Rloaded runtime mod jar, loaded with the base set. */
    val runtimeMod: Path,
    /** Shown by the game as the launcher version. */
    val launcherVersion: String,
    val maxMemoryMiB: Int = DEFAULT_MAX_MEMORY_MIB,
) {
    private companion object {
        const val DEFAULT_MAX_MEMORY_MIB = 4096
    }
}

/** A `${placeholder}` in Mojang's launch arguments that Rloaded does not know how to fill. */
public data class UnknownPlaceholder(
    val name: String,
)

/** Builds the command line that starts the client on the pinned runtime, with the agent and the base set. */
public object ClientCommand {
    private val PLACEHOLDER = Regex("""\$\{([a-zA-Z_]+)}""")

    /** Returns the full command, executable first. */
    public fun build(
        installed: InstalledGame,
        settings: LaunchSettings,
        assetsDirectory: Path,
        nativesDirectory: Path,
    ): Outcome<List<String>, UnknownPlaceholder> {
        val values =
            mapOf(
                "natives_directory" to nativesDirectory.toString(),
                "launcher_name" to "rloaded",
                "launcher_version" to settings.launcherVersion,
                "classpath" to installed.classpath.joinToString(File.pathSeparator),
                "auth_player_name" to settings.profile.name,
                "version_name" to installed.game.id,
                "game_directory" to settings.gameDirectory.toString(),
                "assets_root" to assetsDirectory.toString(),
                "assets_index_name" to installed.game.assetIndexId,
                "auth_uuid" to settings.profile.uuid.toString(),
                // Offline mode: no Microsoft session, so no token, client id or Xbox user id.
                "auth_access_token" to "0",
                "clientid" to "0",
                "auth_xuid" to "0",
                "version_type" to "release",
            ) +
                listOfNotNull(
                    settings.server?.let { "quickPlayMultiplayer" to it.toString() },
                    installed.loggingConfig?.let { "path" to it.toString() },
                )
        val rloaded =
            listOf(
                "-Xmx${settings.maxMemoryMiB}M",
                "-XX:+AllowEnhancedClassRedefinition",
                "-javaagent:${settings.agentJar}",
                "-Dfabric.addMods=${(installed.mods + listOf(settings.runtimeMod)).joinToString(File.pathSeparator)}",
            )
        val logging = listOfNotNull(installed.game.logging?.argument)
        return (installed.game.jvmArguments + logging).traverse { fill(it, values) }.flatMap { jvm ->
            installed.game.gameArguments.traverse { fill(it, values) }.map { game ->
                listOf(installed.java.toString()) + rloaded + jvm + RuntimePins.FABRIC_CLIENT_MAIN_CLASS + game
            }
        }
    }

    private fun fill(
        argument: String,
        values: Map<String, String>,
    ): Outcome<String, UnknownPlaceholder> {
        val unknown = PLACEHOLDER.findAll(argument).map { it.groupValues[1] }.firstOrNull { it !in values }
        return if (unknown != null) {
            Outcome.Err(UnknownPlaceholder(unknown))
        } else {
            Outcome.Ok(PLACEHOLDER.replace(argument) { values.getValue(it.groupValues[1]) })
        }
    }
}
