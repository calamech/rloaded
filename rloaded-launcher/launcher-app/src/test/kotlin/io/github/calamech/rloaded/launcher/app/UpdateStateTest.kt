package io.github.calamech.rloaded.launcher.app

import io.github.calamech.rloaded.core.download.Artifact
import io.github.calamech.rloaded.launcher.update.Update
import io.github.calamech.rloaded.launcher.update.Version
import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateStateTest {
    private val version = Version(1, 1, 0)
    private val update =
        Update(
            version,
            "Rloaded-1.1.0-macos-arm64.dmg",
            Artifact("https://example.org/a.dmg", (Checksum.parse(HashAlgorithm.SHA256, "a".repeat(64)) as Outcome.Ok).value, 1),
        )

    /** A behavioral fake: it records what the state asked for, and answers what the test set. */
    private class FakePorts(
        var latest: Outcome<Update?, String>,
        var download: Outcome<Path, String> = Outcome.Ok(Path.of("/updates/a.dmg")),
        var install: Outcome<Unit, InstallRefusal> = Outcome.Ok(Unit),
    ) : UpdatePorts {
        val downloads = mutableListOf<Update>()
        val installs = mutableListOf<Path>()

        override suspend fun latest(): Outcome<Update?, String> = latest

        override suspend fun download(update: Update): Outcome<Path, String> = download.also { downloads += update }

        override fun install(installer: Path): Outcome<Unit, InstallRefusal> = install.also { installs.add(installer) }
    }

    private val logs = mutableListOf<String>()
    private var quits = 0

    private fun state(ports: FakePorts): UpdateState =
        UpdateState(ports, CoroutineScope(UnconfinedTestDispatcher()), { logs += it }) { quits++ }

    @Test
    fun `offers a newer release, and installs it then quits when the player accepts`() {
        val ports = FakePorts(Outcome.Ok(update))
        val state = state(ports)

        state.check()
        assertEquals(UpdateOffer.Available(version), state.offer.value)
        state.accept()

        assertEquals(UpdateOffer.Installing, state.offer.value)
        assertEquals(listOf(update), ports.downloads)
        assertEquals(listOf(Path.of("/updates/a.dmg")), ports.installs)
        assertEquals(1, quits)
    }

    @Test
    fun `says nothing when there is no newer release, or when the check fails, which goes to the log`() {
        val none = state(FakePorts(Outcome.Ok(null)))
        none.check()
        assertEquals(UpdateOffer.None, none.offer.value)

        val failing = state(FakePorts(Outcome.Err("GitHub cannot be reached")))
        failing.check()
        assertEquals(UpdateOffer.None, failing.offer.value)
        assertEquals(listOf("Update check failed: GitHub cannot be reached"), logs)
    }

    @Test
    fun `not now hides the offer and installs nothing`() {
        val ports = FakePorts(Outcome.Ok(update))
        val state = state(ports)
        state.check()

        state.decline()
        state.accept()

        assertEquals(UpdateOffer.None, state.offer.value)
        assertEquals(emptyList(), ports.downloads)
    }

    @Test
    fun `a download that fails is shown and can be tried again`() {
        val ports = FakePorts(Outcome.Ok(update), download = Outcome.Err("hash mismatch"))
        val state = state(ports)
        state.check()

        state.accept()
        assertEquals(UpdateOffer.Failed(UpdateFailure.DOWNLOAD), state.offer.value)
        assertEquals(0, quits)

        ports.download = Outcome.Ok(Path.of("/updates/a.dmg"))
        state.accept()
        assertEquals(UpdateOffer.Installing, state.offer.value)
    }

    @Test
    fun `an installer that cannot start is shown with its reason, and the launcher stays`() {
        val ports =
            FakePorts(Outcome.Ok(update), install = Outcome.Err(InstallRefusal(UpdateFailure.LOCATION, "read-only /Volumes/Rloaded")))
        val state = state(ports)
        state.check()

        state.accept()

        assertEquals(UpdateOffer.Failed(UpdateFailure.LOCATION), state.offer.value)
        assertEquals("Installing the update failed: read-only /Volumes/Rloaded", logs.last())
        assertEquals(0, quits)
    }
}
