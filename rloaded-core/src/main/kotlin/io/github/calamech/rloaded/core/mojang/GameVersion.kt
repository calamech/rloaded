package io.github.calamech.rloaded.core.mojang

import io.github.calamech.rloaded.core.download.Artifact

/** A game version resolved for one platform: every file it needs and its launch argument templates. */
public data class GameVersion(
    val id: String,
    val mainClass: String,
    /** The Java version the game needs. */
    val javaMajorVersion: Int,
    val client: Artifact,
    val server: Artifact,
    /** Libraries for this platform, in classpath order. */
    val libraries: List<Artifact>,
    val assetIndexId: String,
    val assetIndex: Artifact,
    val logging: Logging?,
    /** JVM arguments for this platform and these features, with `${placeholders}` left in. */
    val jvmArguments: List<String>,
    /** Game arguments for this platform and these features, with `${placeholders}` left in. */
    val gameArguments: List<String>,
)

/** The client logging configuration: a JVM argument template with `${path}`, and the file it points to. */
public data class Logging(
    val argument: String,
    val fileName: String,
    val config: Artifact,
)

/** A game asset: where the game looks for it, and the file to download. */
public data class Asset(
    val path: String,
    val artifact: Artifact,
)

/** Launch features that Mojang's argument rules test for. */
public object LaunchFeatures {
    /** The launcher can start the game straight into a server. */
    public const val QUICK_PLAY_MULTIPLAYER: String = "is_quick_play_multiplayer"
}
