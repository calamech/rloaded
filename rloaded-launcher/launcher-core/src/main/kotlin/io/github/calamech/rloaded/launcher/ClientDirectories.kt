package io.github.calamech.rloaded.launcher

import java.nio.file.Path

/** Layout of the launcher's data directory. */
public class ClientDirectories(
    /** The data directory itself. */
    public val root: Path,
) {
    /** Installed Java runtimes. */
    public val runtimes: Path get() = root.resolve("runtimes")

    /** Downloaded runtime archives, by hash. */
    public val runtimeArchives: Path get() = root.resolve("runtimes/archives")

    /** Game jars and libraries, by hash. */
    public val libraries: Path get() = root.resolve("minecraft/libraries")

    /** Version files and asset indexes, by hash. */
    public val metadata: Path get() = root.resolve("minecraft/metadata")

    /** The assets directory the game reads, with `objects/` by hash and `indexes/` by id. */
    public val assets: Path get() = root.resolve("minecraft/assets")

    /** Mod jars, by SHA-512. */
    public val mods: Path get() = root.resolve("store/sha512")

    /** The servers the player trusts, by key. */
    public val trustStore: Path get() = root.resolve("trust.json")

    /** The game directory of a server or a world: options, saves and logs. */
    public fun profile(name: String): Path = root.resolve("profiles").resolve(name)
}
