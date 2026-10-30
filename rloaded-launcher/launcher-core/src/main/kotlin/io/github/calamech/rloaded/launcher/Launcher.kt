package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.core.mojang.LaunchFeatures
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.mapError
import java.nio.file.Path

/** Why the game could not be started. */
public sealed interface LaunchFailure {
    /** The runtime or the game files could not be installed. */
    public data class Install(
        val failure: InstallFailure,
    ) : LaunchFailure

    /** The game's launch arguments need a value Rloaded does not have. */
    public data class Command(
        val placeholder: UnknownPlaceholder,
    ) : LaunchFailure

    /** The game process could not start. */
    public data class Start(
        val failure: StartFailure,
    ) : LaunchFailure
}

/** Starts a process: the command, its working directory and its log file. */
public fun interface ProcessStarter {
    /** Starts [command] in [directory], writing its output to [log]. */
    public fun start(
        command: List<String>,
        directory: Path,
        log: Path,
    ): Outcome<Process, StartFailure>
}

/** Installs what the game needs, then starts it, joining [ServerAddress] when one is given. */
public class Launcher(
    private val installer: GameInstaller,
    private val directories: ClientDirectories,
    private val agentJar: Path,
    private val runtimeMod: Path,
    private val launcherVersion: String,
    private val processes: ProcessStarter = ProcessStarter(GameProcess::start),
) {
    /** Starts the game for [profile]. [onProgress] receives the files installed so far and the total. */
    public suspend fun play(
        profile: OfflineProfile,
        server: ServerAddress?,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Outcome<Process, LaunchFailure> {
        val features = if (server == null) emptySet() else setOf(LaunchFeatures.QUICK_PLAY_MULTIPLAYER)
        val gameDirectory = directories.profile(profileName(server))
        return installer
            .install(features, onProgress)
            .mapError<InstalledGame, InstallFailure, LaunchFailure> { LaunchFailure.Install(it) }
            .flatMap { installed ->
                val settings =
                    LaunchSettings(
                        profile,
                        gameDirectory,
                        server,
                        agentJar,
                        runtimeMod,
                        launcherVersion,
                        directories.mods,
                        directories.trustStore,
                    )
                ClientCommand
                    .build(installed, settings, directories.assets, gameDirectory.resolve("natives"))
                    .mapError<List<String>, UnknownPlaceholder, LaunchFailure> { LaunchFailure.Command(it) }
            }.flatMap { command ->
                processes
                    .start(command, gameDirectory, gameDirectory.resolve("logs/launcher.log"))
                    .mapError { LaunchFailure.Start(it) }
            }
    }

    // One game directory per server, so options and saves of different servers stay apart.
    private fun profileName(server: ServerAddress?): String =
        if (server ==
            null
        ) {
            "default"
        } else {
            "server-" + "${server.host}-${server.port}".replace(Regex("[^A-Za-z0-9.-]"), "_")
        }
}
