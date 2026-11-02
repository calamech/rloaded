package io.github.calamech.rloaded.launcher.app

import io.github.calamech.rloaded.launcher.update.Update
import io.github.calamech.rloaded.launcher.update.Version
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/** What the window says about a newer launcher. */
public sealed interface UpdateOffer {
    /** Nothing to say: no newer release, or the player said not now. */
    public data object None : UpdateOffer

    /** A newer release exists; the player may install it. */
    public data class Available(
        val version: Version,
    ) : UpdateOffer

    /** The installer is being downloaded. */
    public data class Downloading(
        val version: Version,
    ) : UpdateOffer

    /** The installer runs; the launcher quits and starts again once it is done. */
    public data object Installing : UpdateOffer

    /** The update the player asked for did not install; [reason] says what to do. */
    public data class Failed(
        val reason: UpdateFailure,
    ) : UpdateOffer
}

/** Why an accepted update did not install, in terms the window has a message for. */
public enum class UpdateFailure {
    /** The installer could not be downloaded or did not match its hash. */
    DOWNLOAD,

    /** The launcher runs from somewhere it cannot replace, such as its disk image. */
    LOCATION,

    /** The installer could not be started. */
    INSTALL,
}

/** The steps of an update, each behind a port so the state is tested without GitHub or installers. */
public interface UpdatePorts {
    /** The newer release, `null` when there is none, or the reason the check failed, in words for the log. */
    public suspend fun latest(): Outcome<Update?, String>

    /** The verified installer of [update] on disk, or the reason it is not, in words for the log. */
    public suspend fun download(update: Update): Outcome<Path, String>

    /** Starts the installation of [installer]; the launcher must quit right after. */
    public fun install(installer: Path): Outcome<Unit, InstallRefusal>
}

/** Why an installer was not started. */
public data class InstallRefusal(
    val failure: UpdateFailure,
    val detail: String,
)

/**
 * Asks once, at start, whether a newer launcher exists, and installs it when the player accepts. A
 * failed check is only logged: the player did not ask for it.
 */
public class UpdateState(
    private val ports: UpdatePorts,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val quit: () -> Unit,
) {
    private val mutableOffer = MutableStateFlow<UpdateOffer>(UpdateOffer.None)
    private var update: Update? = null

    /** What the window shows. */
    public val offer: StateFlow<UpdateOffer> = mutableOffer.asStateFlow()

    /** Looks for a newer release. */
    public fun check() {
        scope.launch {
            when (val latest = ports.latest()) {
                is Outcome.Ok -> {
                    update = latest.value
                    latest.value?.let { mutableOffer.value = UpdateOffer.Available(it.version) }
                }

                is Outcome.Err -> {
                    log("Update check failed: ${latest.error}")
                }
            }
        }
    }

    /** Downloads and installs the offered release, then quits. */
    public fun accept() {
        val wanted = update ?: return
        if (mutableOffer.value !is UpdateOffer.Available && mutableOffer.value !is UpdateOffer.Failed) return
        mutableOffer.value = UpdateOffer.Downloading(wanted.version)
        scope.launch {
            when (val installer = ports.download(wanted)) {
                is Outcome.Err -> {
                    log("Downloading the update failed: ${installer.error}")
                    mutableOffer.value = UpdateOffer.Failed(UpdateFailure.DOWNLOAD)
                }

                is Outcome.Ok -> {
                    install(installer.value, wanted)
                }
            }
        }
    }

    /** Hides the offer until the next start. */
    public fun decline() {
        if (mutableOffer.value is UpdateOffer.Available || mutableOffer.value is UpdateOffer.Failed) {
            mutableOffer.value = UpdateOffer.None
        }
    }

    private fun install(
        installer: Path,
        wanted: Update,
    ) {
        when (val started = ports.install(installer)) {
            is Outcome.Ok -> {
                log("Installing Rloaded ${wanted.version}; the launcher quits and starts again")
                mutableOffer.value = UpdateOffer.Installing
                quit()
            }

            is Outcome.Err -> {
                log("Installing the update failed: ${started.error.detail}")
                mutableOffer.value = UpdateOffer.Failed(started.error.failure)
            }
        }
    }
}
