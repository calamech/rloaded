package io.github.calamech.rloaded.launcher.app

import io.github.calamech.rloaded.core.Outcome
import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.mojang.MetadataError
import io.github.calamech.rloaded.core.runtime.ExtractionError
import io.github.calamech.rloaded.core.runtime.InstallError
import io.github.calamech.rloaded.launcher.InstallFailure
import io.github.calamech.rloaded.launcher.LaunchFailure
import io.github.calamech.rloaded.launcher.OfflineProfile
import io.github.calamech.rloaded.launcher.ServerAddress
import io.github.calamech.rloaded.launcher.StartFailure
import io.github.calamech.rloaded.launcher.UnknownPlaceholder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherStateTest {
    /** A game process that exits with [exitCode] when told to. */
    private class FakeProcess(
        private val exitCode: Int,
    ) : Process() {
        val exit = CompletableFuture<Process>()

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int = exit.get().let { exitCode }

        override fun exitValue(): Int = exitCode

        override fun destroy() {
            exit.complete(this)
        }

        override fun onExit(): CompletableFuture<Process> = exit
    }

    private val logged = mutableListOf<String>()

    @Test
    fun `refuses an invalid player name without starting anything`() =
        runTest {
            var calls = 0
            val state =
                LauncherState({ _, _, _ -> Outcome.Err(LaunchFailure.Start(StartFailure("unused"))).also { calls++ } }, backgroundScope) {}

            state.play("x", "localhost")

            assertEquals(Status.Failed(FailureReason.PLAYER_NAME), state.status.value)
            assertEquals(0, calls)
        }

    @Test
    fun `refuses an invalid server address`() =
        runTest {
            val state = LauncherState({ _, _, _ -> Outcome.Err(LaunchFailure.Start(StartFailure("unused"))) }, backgroundScope) {}

            state.play("Dev", "host:99999")

            assertEquals(Status.Failed(FailureReason.SERVER_ADDRESS), state.status.value)
        }

    @Test
    fun `shows progress, then the running game, then its exit code`() =
        runTest(UnconfinedTestDispatcher()) {
            val process = FakeProcess(exitCode = 3)
            val progressed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var joined: Pair<OfflineProfile, ServerAddress?>? = null
            val state =
                LauncherState({ profile, server, onProgress ->
                    joined = profile to server
                    onProgress(1, 4)
                    progressed.complete(Unit)
                    release.await()
                    Outcome.Ok(process)
                }, backgroundScope) {}

            state.play("Dev", "localhost:25566")
            progressed.await()

            assertEquals(Status.Installing(1, 4), state.status.value)
            assertFalse(state.canPlay)
            assertEquals(ServerAddress("localhost", 25566), joined?.second)
            release.complete(Unit)
            assertEquals(Status.Playing, state.status.value)
            process.destroy()
            assertEquals(Status.Stopped(3), state.status.value)
            assertTrue(state.canPlay)
        }

    @Test
    fun `opens the title screen when the server is empty`() =
        runTest(UnconfinedTestDispatcher()) {
            var server: ServerAddress? = ServerAddress("unset")
            val state =
                LauncherState({ _, address, _ ->
                    server = address
                    Outcome.Err(LaunchFailure.Start(StartFailure("stop here")))
                }, backgroundScope) {}

            state.play("Dev", "  ")

            assertEquals(null, server)
        }

    @Test
    fun `logs the details of a failure and shows its reason`() =
        runTest(UnconfinedTestDispatcher()) {
            val state =
                LauncherState({ _, _, _ -> Outcome.Err(LaunchFailure.Start(StartFailure("exec format error"))) }, backgroundScope) {
                    logged +=
                        it
                }

            state.play("Dev", "")

            assertEquals(Status.Failed(FailureReason.LAUNCH), state.status.value)
            assertTrue(logged.single().contains("exec format error"))
        }

    @Test
    fun `gives every failure a reason the window can show`() {
        val storage = InstallFailure.Storage(Path.of("x"), "disk full")
        assertEquals(FailureReason.DOWNLOADS, LauncherState.reasonOf(LaunchFailure.Install(InstallFailure.Downloads(emptyList(), 0))))
        assertEquals(
            FailureReason.RUNTIME,
            LauncherState.reasonOf(LaunchFailure.Install(InstallFailure.Runtime(InstallError.Extraction(ExtractionError.Failed("x"))))),
        )
        assertEquals(
            FailureReason.METADATA,
            LauncherState.reasonOf(LaunchFailure.Install(InstallFailure.Metadata(MetadataError.UnknownVersion("26.3")))),
        )
        assertEquals(FailureReason.STORAGE, LauncherState.reasonOf(LaunchFailure.Install(storage)))
        assertEquals(FailureReason.LAUNCH, LauncherState.reasonOf(LaunchFailure.Command(UnknownPlaceholder("x"))))
        assertEquals(
            FailureReason.RUNTIME,
            LauncherState.reasonOf(LaunchFailure.Install(InstallFailure.Runtime(InstallError.Download(DownloadError.Refused("u", "r"))))),
        )
    }
}
