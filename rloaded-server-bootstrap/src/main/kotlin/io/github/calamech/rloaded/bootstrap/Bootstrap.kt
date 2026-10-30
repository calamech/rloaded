package io.github.calamech.rloaded.bootstrap

import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.mapError
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories

/** Exit codes of `rloaded-server.jar`. */
public object ExitCode {
    /** Stopped normally, or set up a new folder. */
    public const val OK: Int = 0

    /** Something the admin must fix; the message says what. */
    public const val FAILED: Int = 1

    /** Unknown command line arguments. */
    public const val USAGE: Int = 2

    /** Asked by the runtime mod: start the server again. */
    public const val RESTART: Int = 75
}

/** Starts the server process and returns its exit code. */
public fun interface ServerProcess {
    /** Runs [command] in [directory] until it exits. */
    public fun run(
        command: List<String>,
        directory: Path,
    ): Int
}

/**
 * The bootstrap: sets up a new server folder, then on later starts installs the pinned runtime and
 * starts Fabric's server on it with the agent, again each time the server asks for a restart.
 */
public class Bootstrap(
    private val folder: ServerFolder,
    private val installer: ServerInstaller,
    private val process: ServerProcess,
    private val out: PrintStream,
    private val maxRestarts: Int = MAX_RESTARTS,
) {
    /** Runs with the command line [arguments] and returns the process exit code. */
    public suspend fun run(arguments: List<String>): Int {
        val dev = arguments == listOf("--dev")
        return when {
            arguments.isNotEmpty() && !dev -> fail("Usage: java -jar rloaded-server.jar [--dev]", ExitCode.USAGE)
            folder.initialize() -> firstStart()
            else -> start(dev)
        }
    }

    private fun firstStart(): Int {
        out.println("Rloaded set up this folder: ${folder.root.toAbsolutePath().normalize()}")
        out.println("1. Read the Minecraft EULA (https://aka.ms/MinecraftEULA) and set eula=true in eula.txt if you accept it.")
        out.println("2. Check rloaded-server.toml.")
        out.println("3. Start rloaded-server.jar again.")
        return ExitCode.OK
    }

    private suspend fun start(dev: Boolean): Int {
        val command =
            ServerConfig
                .parse(folder.readConfig())
                .mapError { "rloaded-server.toml is invalid: $it" }
                .flatMap { config ->
                    if (folder.eulaAccepted()) {
                        Outcome.Ok(
                            config,
                        )
                    } else {
                        Outcome.Err("The Minecraft EULA is not accepted. Set eula=true in eula.txt to accept it.")
                    }
                }.flatMap { config ->
                    val refusal = if (dev) folder.prepareDevMode() else null
                    if (refusal == null) Outcome.Ok(config) else Outcome.Err(refusal)
                }.flatMap { config ->
                    out.println("Installing the runtime for ${folder.serverName(config)}…")
                    installer.install().flatMap { installed ->
                        writeBundled("agent", "rloaded-agent.jar").flatMap { agent ->
                            writeBundled("runtime", "rloaded-runtime.jar").map { runtime ->
                                ServerCommand.build(installed, agent, listOf(runtime), config.server.maxMemoryMiB)
                            }
                        }
                    }
                }
        return when (command) {
            is Outcome.Ok -> runUntilStopped(command.value)
            is Outcome.Err -> fail(command.error)
        }
    }

    private fun runUntilStopped(command: List<String>): Int {
        var restarts = 0
        while (true) {
            val exit = process.run(command, folder.root)
            if (exit != ExitCode.RESTART) return exit
            if (restarts == maxRestarts) return fail("The server asked for $maxRestarts restarts in a row; stopping.")
            restarts++
            out.println("Restarting the server.")
        }
    }

    // The agent and the runtime mod ship inside the bootstrap, so they always match its version.
    private fun writeBundled(
        directory: String,
        name: String,
    ): Outcome<Path, String> {
        val target = folder.data.resolve(directory).resolve(name)
        val resource = checkNotNull(Bootstrap::class.java.getResourceAsStream(name)) { "$name is missing from the bootstrap" }
        return try {
            target.parent.createDirectories()
            resource.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
            Outcome.Ok(target)
        } catch (e: IOException) {
            Outcome.Err("cannot write $name: ${e.message}")
        }
    }

    private fun fail(
        message: String,
        code: Int = ExitCode.FAILED,
    ): Int {
        out.println(message)
        return code
    }

    private companion object {
        const val MAX_RESTARTS = 10
    }
}
