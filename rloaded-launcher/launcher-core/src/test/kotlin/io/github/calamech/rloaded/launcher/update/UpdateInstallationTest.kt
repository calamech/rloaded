package io.github.calamech.rloaded.launcher.update

import io.github.calamech.rloaded.core.mojang.OperatingSystem
import io.github.calamech.rloaded.protocol.Outcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateInstallationTest {
    @TempDir
    lateinit var dir: Path

    private val os = System.getProperty("os.name")

    @Test
    fun `replaces the bundle of the running app on macOS`() {
        val bundle = dir.resolve("Applications/Rloaded.app")
        val launcher = bundle.resolve("Contents/MacOS/Rloaded")
        dir.resolve("Applications").createDirectories()

        val command = ok(UpdateInstallation(OperatingSystem.MACOS, dir.resolve("work")).command(Path.of("/u/up.dmg"), launcher, 42))

        assertEquals(
            listOf(
                "/bin/sh",
                dir.resolve("work/install-macos.sh").toString(),
                "42",
                Path.of("/u/up.dmg").toString(),
                bundle.toString(),
                "/usr/bin/open",
            ),
            command,
        )
    }

    @Test
    fun `refuses on macOS when the app's folder cannot be written to, as on its disk image`() {
        assumeTrue(!os.startsWith("Windows"))
        val folder = dir.resolve("Volumes/Rloaded").createDirectories()
        val launcher = folder.resolve("Rloaded.app/Contents/MacOS/Rloaded")
        folder.setPosixFilePermissions(
            java.nio.file.attribute.PosixFilePermissions
                .fromString("r-x------"),
        )
        try {
            assertEquals(
                Outcome.Err(InstallationError.ReadOnly(folder)),
                UpdateInstallation(OperatingSystem.MACOS, dir.resolve("work")).command(Path.of("/u/up.dmg"), launcher, 42),
            )
        } finally {
            folder.setPosixFilePermissions(
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rwx------"),
            )
        }
    }

    @Test
    fun `refuses on macOS when the launcher does not run from an app bundle`() {
        val launcher = dir.resolve("build/bin/java")

        assertEquals(
            Outcome.Err(InstallationError.NotInstalled(launcher)),
            UpdateInstallation(OperatingSystem.MACOS, dir.resolve("work")).command(Path.of("/u/up.dmg"), launcher, 42),
        )
    }

    @Test
    fun `installs with pkexec and dpkg on Linux, and with msiexec through PowerShell on Windows`() {
        val linux = ok(UpdateInstallation(OperatingSystem.LINUX, dir).command(Path.of("/u/up.deb"), Path.of("/opt/rloaded/bin/Rloaded"), 7))
        assertEquals(listOf("pkexec", "dpkg", "-i"), linux.takeLast(3))
        assertEquals(listOf("7", Path.of("/u/up.deb").toString(), Path.of("/opt/rloaded/bin/Rloaded").toString()), linux.subList(2, 5))

        val windows = ok(UpdateInstallation(OperatingSystem.WINDOWS, dir).command(Path.of("up.msi"), Path.of("Rloaded.exe"), 7))
        assertEquals(listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File"), windows.take(5))
        assertEquals(listOf("-LauncherPid", "7", "-Msi", "up.msi", "-App", "Rloaded.exe"), windows.takeLast(6))
    }

    @Test
    fun `the macOS script swaps the bundle from the disk image, then starts the new one`() {
        assumeTrue(os.startsWith("Mac"))
        val bundle = dir.resolve("Applications/Rloaded.app")
        fakeBundle(bundle, "old")
        val image = dir.resolve("image")
        fakeBundle(image.resolve("Rloaded.app"), "new")
        val dmg = dir.resolve("update.dmg")
        run(listOf("hdiutil", "create", "-quiet", "-srcfolder", image.toString(), "-format", "UDZO", dmg.toString()))
        val opened = dir.resolve("opened.txt")
        val recorder = script("record.sh", "#!/bin/sh\necho \"$1\" > '$opened'\n")

        val command =
            ok(
                UpdateInstallation(
                    OperatingSystem.MACOS,
                    dir.resolve("work"),
                ).command(dmg, bundle.resolve("Contents/MacOS/Rloaded"), endedPid()),
            )
        assertEquals(0, run(command.dropLast(1) + recorder.toString()))

        assertEquals("new", bundle.resolve("Contents/MacOS/Rloaded").readText())
        assertEquals(bundle.toString(), opened.readText().trim())
        assertTrue(!dir.resolve("Applications/Rloaded.app.old").exists() && !dir.resolve("Applications/Rloaded.app.new").exists())
    }

    @Test
    fun `the Linux script installs the package, then starts the launcher, even when the install fails`() {
        assumeTrue(!os.startsWith("Windows"))
        val installed = dir.resolve("installed.txt")
        val started = dir.resolve("started.txt")
        val launcher = script("launcher.sh", "#!/bin/sh\necho started > '$started'\n")
        val installer = script("dpkg.sh", "#!/bin/sh\necho \"$1\" > '$installed'\nexit \$FAIL\n")

        val command = ok(UpdateInstallation(OperatingSystem.LINUX, dir.resolve("work")).command(Path.of("/u/up.deb"), launcher, endedPid()))
        val withFake = command.dropLast(3) + installer.toString()
        assertEquals(0, run(withFake, mapOf("FAIL" to "0")))
        assertEquals("/u/up.deb", installed.readText().trim())
        waitFor(started)

        started.toFile().delete()
        assertEquals(3, run(withFake, mapOf("FAIL" to "3")))
        waitFor(started)
    }

    @Test
    fun `the Windows script runs the installer, then starts the launcher`() {
        assumeTrue(os.startsWith("Windows"))
        val installed = dir.resolve("installed.txt")
        val started = dir.resolve("started.txt")
        val installer = dir.resolve("msiexec.cmd").apply { writeText("@echo %* > \"$installed\"\r\n") }
        val launcher = dir.resolve("launcher.cmd").apply { writeText("@echo started > \"$started\"\r\n") }

        val command =
            ok(UpdateInstallation(OperatingSystem.WINDOWS, dir.resolve("work")).command(Path.of("C:\\u\\up.msi"), launcher, endedPid()))
        assertEquals(0, run(command + listOf("-Installer", installer.toString())))

        assertTrue("/i" in installed.readText() && "C:\\u\\up.msi" in installed.readText() && "/passive" in installed.readText())
        waitFor(started)
    }

    private fun fakeBundle(
        bundle: Path,
        content: String,
    ) {
        bundle.resolve("Contents/MacOS").createDirectories()
        bundle.resolve("Contents/MacOS/Rloaded").writeText(content)
    }

    private fun script(
        name: String,
        text: String,
    ): Path =
        dir.resolve(name).apply {
            writeText(text)
            setPosixFilePermissions(
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rwx------"),
            )
        }

    // A process that already ended: the scripts wait for the launcher, which here has quit.
    private fun endedPid(): Long {
        val process = ProcessBuilder(if (os.startsWith("Windows")) listOf("cmd", "/c", "exit") else listOf("true")).start()
        process.waitFor()
        return process.pid()
    }

    private fun run(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
    ): Int {
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("output.txt").toFile())
                .apply { environment().putAll(environment) }
                .start()
        assertTrue(process.waitFor(1, TimeUnit.MINUTES), "the command did not end")
        return process.exitValue()
    }

    private fun waitFor(file: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!file.exists() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(file.exists(), "$file was not written")
    }

    private fun <T> ok(outcome: Outcome<T, InstallationError>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("unexpected ${outcome.error}")
        }
}
