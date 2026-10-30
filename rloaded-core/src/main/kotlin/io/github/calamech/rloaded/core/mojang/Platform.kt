package io.github.calamech.rloaded.core.mojang

import io.github.calamech.rloaded.protocol.Outcome

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

/** An operating system or processor Rloaded does not support. */
public data class UnsupportedPlatform(
    val osName: String,
    val osArch: String,
)

/** Detection of the current platform. */
public object Platforms {
    /** Reads [osName] and [osArch] as the JVM reports them in `os.name` and `os.arch`. */
    public fun detect(
        osName: String,
        osArch: String,
    ): Outcome<Platform, UnsupportedPlatform> {
        val os =
            when {
                osName.startsWith("Windows") -> OperatingSystem.WINDOWS
                osName.startsWith("Mac") -> OperatingSystem.MACOS
                osName.startsWith("Linux") -> OperatingSystem.LINUX
                else -> null
            }
        val architecture =
            when (osArch) {
                "amd64", "x86_64" -> Architecture.X86_64
                "aarch64", "arm64" -> Architecture.ARM64
                else -> null
            }
        return if (os == null || architecture == null) {
            Outcome.Err(UnsupportedPlatform(osName, osArch))
        } else {
            Outcome.Ok(Platform(os, architecture))
        }
    }

    /** The platform of this JVM. */
    public fun current(): Outcome<Platform, UnsupportedPlatform> = detect(System.getProperty("os.name"), System.getProperty("os.arch"))
}
