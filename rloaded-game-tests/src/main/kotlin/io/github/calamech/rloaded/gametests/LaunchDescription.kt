package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.core.runtime.ServerLaunch
import io.github.calamech.rloaded.protocol.Outcome
import java.nio.file.Path

/** Writes [launch] where the bootstrap would in the server folder [directory], and returns it pointing there. */
internal fun describe(
    launch: ServerLaunch,
    directory: Path,
): ServerLaunch {
    val file = directory.resolve("rloaded").resolve("server-launch.json")
    return when (val written = launch.write(file)) {
        is Outcome.Ok -> launch.describedBy(file)
        is Outcome.Err -> failCheck(written.error)
    }
}
