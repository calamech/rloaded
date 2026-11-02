package io.github.calamech.rloaded.launcher.app

import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.download.Http
import io.github.calamech.rloaded.core.mojang.Platform
import io.github.calamech.rloaded.launcher.update.InstallationError
import io.github.calamech.rloaded.launcher.update.ReleaseSource
import io.github.calamech.rloaded.launcher.update.Update
import io.github.calamech.rloaded.launcher.update.UpdateCheck
import io.github.calamech.rloaded.launcher.update.UpdateError
import io.github.calamech.rloaded.launcher.update.UpdateInstallation
import io.github.calamech.rloaded.launcher.update.Version
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.mapError
import java.io.IOException
import java.nio.file.Path

/**
 * The real update steps: releases from GitHub, installers kept in [updates], and the install
 * command started as a process of its own, which outlives the launcher.
 */
internal class GitHubUpdates(
    private val http: Http,
    private val platform: Platform,
    private val updates: Path,
    private val log: Path,
) : UpdatePorts {
    private val store = ArtifactStore(updates.resolve("installers"), http)

    override suspend fun latest(): Outcome<Update?, String> {
        val current =
            Version.parse(LauncherVersion.current)
                ?: return Outcome.Err("the launcher's version ${LauncherVersion.current} is not a release")
        return UpdateCheck(http, ReleaseSource.GITHUB, current, platform)
            .check()
            .mapError<Update?, UpdateError, String> { it.toString() }
    }

    override suspend fun download(update: Update): Outcome<Path, String> =
        store
            .obtain(update.installer, "." + update.fileName.substringAfterLast('.'))
            .mapError<Path, DownloadError, String> { it.toString() }

    override fun install(installer: Path): Outcome<Unit, InstallRefusal> {
        // The packaged launcher's native executable sets this; a launcher run from a build has none.
        val launcher =
            System.getProperty("jpackage.app-path")
                ?: return Outcome.Err(InstallRefusal(UpdateFailure.LOCATION, "the launcher does not run from an installed app"))
        val command =
            when (
                val planned =
                    UpdateInstallation(
                        platform.os,
                        updates.resolve("work"),
                    ).command(installer, Path.of(launcher), ProcessHandle.current().pid())
            ) {
                is Outcome.Ok -> planned.value
                is Outcome.Err -> return Outcome.Err(refusal(planned.error))
            }
        return try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start()
            Outcome.Ok(Unit)
        } catch (e: IOException) {
            Outcome.Err(InstallRefusal(UpdateFailure.INSTALL, "cannot start ${command.first()}: ${e.message}"))
        }
    }

    private fun refusal(error: InstallationError): InstallRefusal =
        when (error) {
            is InstallationError.NotInstalled -> InstallRefusal(UpdateFailure.LOCATION, "not an installed app: ${error.launcher}")
            is InstallationError.ReadOnly -> InstallRefusal(UpdateFailure.LOCATION, "cannot write to ${error.folder}")
            is InstallationError.CannotWrite -> InstallRefusal(UpdateFailure.INSTALL, "cannot write ${error.script}: ${error.reason}")
        }
}
