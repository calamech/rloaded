package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.launcher.ClientDirectories
import io.github.calamech.rloaded.launcher.InstalledGame
import java.nio.file.Path

/**
 * What every client of a check is started with. [directories] hold the installed game, cached
 * between runs; [launcherData] holds the mod store and the trust store of this run only, so each run
 * starts as a first join; [environment] is added to the client's own, as the launcher adds its link.
 */
internal data class ClientLaunch(
    val installed: InstalledGame,
    val directories: ClientDirectories,
    val launcherData: ClientDirectories,
    val agent: Path,
    val runtimeMod: Path,
    val mods: List<Path>,
    val environment: Map<String, String> = emptyMap(),
)
