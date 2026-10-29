package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.core.mojang.OperatingSystem
import java.nio.file.Path

/** Where the launcher keeps its data on each operating system. */
public object DataDirectory {
    /** The data directory for [os], given the environment variables [environment] and the user's [home]. */
    public fun of(
        os: OperatingSystem,
        environment: Map<String, String>,
        home: Path,
    ): Path =
        when (os) {
            OperatingSystem.MACOS -> {
                home.resolve("Library/Application Support/Rloaded")
            }

            OperatingSystem.WINDOWS -> {
                nonBlank(environment["APPDATA"])?.let(Path::of)?.resolve("Rloaded")
                    ?: home.resolve("AppData/Roaming/Rloaded")
            }

            OperatingSystem.LINUX -> {
                (nonBlank(environment["XDG_DATA_HOME"])?.let(Path::of) ?: home.resolve(".local/share")).resolve("rloaded")
            }
        }

    private fun nonBlank(value: String?): String? = value?.takeIf { it.isNotBlank() }
}
