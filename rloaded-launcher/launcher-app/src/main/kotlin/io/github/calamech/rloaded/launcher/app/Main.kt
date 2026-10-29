package io.github.calamech.rloaded.launcher.app

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.calamech.rloaded.core.Outcome
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.UrlPolicy
import io.github.calamech.rloaded.core.mojang.Platforms
import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.launcher.ClientDirectories
import io.github.calamech.rloaded.launcher.DataDirectory
import io.github.calamech.rloaded.launcher.GameInstaller
import io.github.calamech.rloaded.launcher.Launcher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess

private const val LAUNCHER_VERSION = "0.1.0-dev"

/** Entry point of the launcher: wires the launcher to its window. */
public fun main() {
    val platform =
        when (val detected = Platforms.current()) {
            is Outcome.Ok -> {
                detected.value
            }

            is Outcome.Err -> {
                System.err.println("Rloaded does not run on ${detected.error.osName} ${detected.error.osArch}.")
                exitProcess(1)
            }
        }
    val directories = ClientDirectories(DataDirectory.of(platform.os, System.getenv(), Path.of(System.getProperty("user.home"))))
    val log = directories.root.resolve("logs/launcher.log")
    val http = KtorHttp(UrlPolicy.PRODUCTION, HttpSettings())
    val launcher =
        Launcher(GameInstaller(http, directories, platform, RuntimePins.CURRENT), directories, writeAgent(directories), LAUNCHER_VERSION)
    application {
        Window(
            onCloseRequest = {
                http.close()
                exitApplication()
            },
            title = "Rloaded",
            state = rememberWindowState(width = 480.dp, height = 460.dp),
        ) {
            val scope = rememberCoroutineScope()
            val state = remember { LauncherState(launcher::play, scope) { append(log, it) } }
            LauncherWindow(state)
        }
    }
}

private fun writeAgent(directories: ClientDirectories): Path {
    val target = directories.root.resolve("agent/rloaded-agent.jar")
    target.parent.createDirectories()
    val resource =
        checkNotNull(LauncherState::class.java.getResourceAsStream("rloaded-agent.jar")) { "the agent jar is missing from the launcher" }
    resource.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
    return target
}

private fun append(
    log: Path,
    line: String,
) {
    log.parent.createDirectories()
    Files.writeString(log, "${Instant.now()} $line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
}
