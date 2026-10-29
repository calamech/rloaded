package io.github.calamech.rloaded.bootstrap

import io.github.calamech.rloaded.core.runtime.RuntimePins
import java.io.File
import java.nio.file.Path

/** The command line that starts Fabric's server on the pinned runtime with the agent. */
public object ServerCommand {
    /**
     * Builds the command for [installed], loading [extraMods] after the base set, with each of
     * [systemProperties] passed as `-Dname=value`.
     */
    public fun build(
        installed: InstalledServer,
        agent: Path,
        extraMods: List<Path>,
        maxMemoryMiB: Int,
        systemProperties: Map<String, String> = emptyMap(),
    ): List<String> =
        listOf(
            installed.java.toString(),
            "-Xmx${maxMemoryMiB}M",
            "-XX:+AllowEnhancedClassRedefinition",
            "-javaagent:$agent",
            "-Dfabric.addMods=${(installed.mods + extraMods).joinToString(File.pathSeparator)}",
        ) + systemProperties.map { (name, value) -> "-D$name=$value" } +
            listOf(
                "-cp",
                installed.classpath.joinToString(File.pathSeparator),
                RuntimePins.FABRIC_SERVER_MAIN_CLASS,
                "nogui",
            )
}
