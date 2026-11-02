package io.github.calamech.rloaded.launcher.update

import io.github.calamech.rloaded.core.mojang.OperatingSystem
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.map
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name

/** Why an update cannot be installed by the running launcher. */
public sealed interface InstallationError {
    /** The launcher does not run from an installed app, as when started from a build. */
    public data class NotInstalled(
        val launcher: Path,
    ) : InstallationError

    /** The folder of the app cannot be written to, as when the app runs from its disk image. */
    public data class ReadOnly(
        val folder: Path,
    ) : InstallationError

    /** The script that installs the update cannot be written. */
    public data class CannotWrite(
        val script: Path,
        val reason: String,
    ) : InstallationError
}

/**
 * Prepares the installation of a downloaded installer: the launcher starts the returned command,
 * then quits. The command waits for the launcher to end, installs the update in place, and starts
 * the launcher again; if the install fails, the old launcher starts.
 */
public class UpdateInstallation(
    private val os: OperatingSystem,
    /** Where the install script is written; it must stay until the command ends. */
    private val workDirectory: Path,
) {
    /**
     * The command that installs [installer] once the process [launcherPid] ended, then starts
     * [launcher], the launcher's own executable.
     */
    public fun command(
        installer: Path,
        launcher: Path,
        launcherPid: Long,
    ): Outcome<List<String>, InstallationError> =
        when (os) {
            OperatingSystem.MACOS -> {
                // The executable is <bundle>.app/Contents/MacOS/<name>; the bundle is what gets replaced.
                val bundle = launcher.parent?.parent?.parent
                val folder = bundle?.parent
                if (bundle == null || folder == null || !bundle.name.endsWith(".app")) {
                    Outcome.Err(InstallationError.NotInstalled(launcher))
                } else if (!Files.isWritable(folder)) {
                    Outcome.Err(InstallationError.ReadOnly(folder))
                } else {
                    script("install-macos.sh").map { script ->
                        listOf(
                            "/bin/sh",
                            script.toString(),
                            launcherPid.toString(),
                            installer.toString(),
                            bundle.toString(),
                            "/usr/bin/open",
                        )
                    }
                }
            }

            OperatingSystem.LINUX -> {
                script("install-linux.sh").map { script ->
                    listOf("/bin/sh", script.toString(), launcherPid.toString(), installer.toString(), launcher.toString()) +
                        listOf("pkexec", "dpkg", "-i")
                }
            }

            OperatingSystem.WINDOWS -> {
                script("install-windows.ps1").map { script ->
                    listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()) +
                        listOf("-LauncherPid", launcherPid.toString(), "-Msi", installer.toString(), "-App", launcher.toString())
                }
            }
        }

    private fun script(name: String): Outcome<Path, InstallationError> {
        val target = workDirectory.resolve(name)
        return try {
            workDirectory.createDirectories()
            val bytes =
                UpdateInstallation::class.java.getResourceAsStream(name)?.use { it.readBytes() }
                    ?: error("the launcher was built without $name")
            Files.write(target, bytes)
            Outcome.Ok(target)
        } catch (e: IOException) {
            Outcome.Err(InstallationError.CannotWrite(target, e.message ?: e.toString()))
        }
    }
}
