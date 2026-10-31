package io.github.calamech.rloaded.bootstrap

import io.github.calamech.rloaded.core.runtime.RuntimePins
import io.github.calamech.rloaded.core.runtime.ServerLaunch
import java.nio.file.Path

/** How the bootstrap starts Fabric's server on the pinned runtime with the agent. */
public object ServerCommand {
    /**
     * The launch of [installed], loading [extraMods] after the base set, with each of
     * [systemProperties] passed as `-Dname=value`. Paths are absolute, so the launch works from any
     * directory.
     */
    public fun launch(
        installed: InstalledServer,
        agent: Path,
        extraMods: List<Path>,
        maxMemoryMiB: Int,
        systemProperties: Map<String, String> = emptyMap(),
    ): ServerLaunch =
        ServerLaunch(
            java = absolute(installed.java),
            maxMemoryMiB = maxMemoryMiB,
            agent = absolute(agent),
            classpath = installed.classpath.map(::absolute),
            mainClass = RuntimePins.FABRIC_SERVER_MAIN_CLASS,
            mods = (installed.mods + extraMods).map(::absolute),
            properties = systemProperties,
        )

    private fun absolute(path: Path): String = path.toAbsolutePath().toString()
}
