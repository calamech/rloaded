package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.core.Outcome
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories

/** Why the game process could not start. */
public data class StartFailure(
    val reason: String,
)

/** Starts the game as a separate process, its output written to a log file. */
public object GameProcess {
    /** Starts [command] in [workingDirectory], sending standard output and errors to [log]. */
    public fun start(
        command: List<String>,
        workingDirectory: Path,
        log: Path,
    ): Outcome<Process, StartFailure> =
        try {
            workingDirectory.createDirectories()
            log.parent.createDirectories()
            Outcome.Ok(
                ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start(),
            )
        } catch (e: IOException) {
            Outcome.Err(StartFailure(e.message ?: e.javaClass.simpleName))
        }
}
