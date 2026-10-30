package io.github.calamech.rloaded.launcher.app

import io.github.calamech.rloaded.launcher.InstallFailure
import io.github.calamech.rloaded.launcher.LaunchFailure
import io.github.calamech.rloaded.launcher.OfflineProfile
import io.github.calamech.rloaded.launcher.ServerAddress
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch

/** What the window shows under the Play button. */
public sealed interface Status {
    /** Nothing running. */
    public data object Ready : Status

    /** Files are being installed. */
    public data class Installing(
        val done: Int,
        val total: Int,
    ) : Status

    /** The game process runs. */
    public data object Playing : Status

    /** The game process exited. */
    public data class Stopped(
        val exitCode: Int,
    ) : Status

    /** Nothing started; [reason] says what the player can do. */
    public data class Failed(
        val reason: FailureReason,
    ) : Status
}

/** What went wrong, in terms the window has a message for. */
public enum class FailureReason {
    /** The player name is not valid. */
    PLAYER_NAME,

    /** The server address is not valid. */
    SERVER_ADDRESS,

    /** Some files could not be downloaded. */
    DOWNLOADS,

    /** The Java runtime could not be installed. */
    RUNTIME,

    /** The game metadata could not be read. */
    METADATA,

    /** A file could not be written. */
    STORAGE,

    /** The game process could not start. */
    LAUNCH,
}

/** Starts the game: the window's only way into the launcher. */
public fun interface PlayAction {
    /** Starts the game for [profile], joining [server] when it is not null. */
    public suspend fun play(
        profile: OfflineProfile,
        server: ServerAddress?,
        onProgress: (Int, Int) -> Unit,
    ): Outcome<Process, LaunchFailure>
}

/**
 * State of the launcher window. Technical details of a failure go to [log]; the window only shows
 * a [FailureReason].
 */
public class LauncherState(
    private val action: PlayAction,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    private val mutableStatus = MutableStateFlow<Status>(Status.Ready)

    /** The current status. */
    public val status: StateFlow<Status> = mutableStatus.asStateFlow()

    /** Whether the Play button can be pressed. */
    public val canPlay: Boolean get() = status.value !is Status.Installing && status.value !is Status.Playing

    /** Starts the game for the typed [playerName] and [serverText], an empty server meaning the title screen. */
    public fun play(
        playerName: String,
        serverText: String,
    ) {
        if (!canPlay) return
        val profile =
            when (val parsed = OfflineProfile.of(playerName)) {
                is Outcome.Ok -> parsed.value
                is Outcome.Err -> return fail(FailureReason.PLAYER_NAME)
            }
        val server =
            when (val parsed = if (serverText.isBlank()) Outcome.Ok(null) else ServerAddress.parse(serverText)) {
                is Outcome.Ok -> parsed.value
                is Outcome.Err -> return fail(FailureReason.SERVER_ADDRESS)
            }
        mutableStatus.value = Status.Installing(0, 0)
        scope.launch {
            when (val result = action.play(profile, server) { done, total -> mutableStatus.value = Status.Installing(done, total) }) {
                is Outcome.Ok -> {
                    watch(result.value)
                }

                is Outcome.Err -> {
                    log("Launch failed: ${result.error}")
                    fail(reasonOf(result.error))
                }
            }
        }
    }

    private suspend fun watch(process: Process) {
        mutableStatus.value = Status.Playing
        val exited = process.onExit().await()
        mutableStatus.value = Status.Stopped(exited.exitValue())
    }

    private fun fail(reason: FailureReason) {
        mutableStatus.value = Status.Failed(reason)
    }

    internal companion object {
        fun reasonOf(failure: LaunchFailure): FailureReason =
            when (failure) {
                is LaunchFailure.Install -> {
                    when (failure.failure) {
                        is InstallFailure.Downloads -> FailureReason.DOWNLOADS
                        is InstallFailure.Runtime -> FailureReason.RUNTIME
                        is InstallFailure.Metadata -> FailureReason.METADATA
                        is InstallFailure.Storage -> FailureReason.STORAGE
                    }
                }

                is LaunchFailure.Command -> {
                    FailureReason.LAUNCH
                }

                is LaunchFailure.Start -> {
                    FailureReason.LAUNCH
                }
            }
    }
}
