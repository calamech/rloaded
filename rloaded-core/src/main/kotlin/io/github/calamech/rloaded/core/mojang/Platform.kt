package io.github.calamech.rloaded.core.mojang

/** Operating systems, with the names Mojang uses in its rules. */
public enum class OperatingSystem(
    internal val mojangName: String,
) {
    /** Microsoft Windows. */
    WINDOWS("windows"),

    /** Apple macOS. */
    MACOS("osx"),

    /** Linux. */
    LINUX("linux"),
}

/** Processor architectures Rloaded runs on. */
public enum class Architecture {
    /** 64-bit x86. */
    X86_64,

    /** 64-bit ARM. */
    ARM64,
}

/** The machine the game runs on. */
public data class Platform(
    val os: OperatingSystem,
    val architecture: Architecture,
)
