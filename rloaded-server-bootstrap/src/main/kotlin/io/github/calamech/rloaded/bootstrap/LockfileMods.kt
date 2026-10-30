package io.github.calamech.rloaded.bootstrap

import io.github.calamech.rloaded.core.lockfile.Lockfile
import io.github.calamech.rloaded.core.lockfile.LockfileError
import io.github.calamech.rloaded.core.lockfile.entries
import io.github.calamech.rloaded.modrinth.InstallError
import io.github.calamech.rloaded.modrinth.InstalledMod
import io.github.calamech.rloaded.modrinth.ModInstaller
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.mapError
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * The mods a server runs from its lockfile, obtained the way a joining player obtains them: each
 * entry checked against Modrinth's record, its file downloaded from that record into the store.
 */
public class LockfileMods(
    private val installer: ModInstaller,
) {
    /**
     * Returns the jars of the mods in the lockfile at [path] that run on the server, in lockfile
     * order. No lockfile means no mods; a broken one or a mod that cannot be obtained is described
     * for the admin.
     */
    public suspend fun obtain(path: Path): Outcome<List<Path>, String> {
        if (!path.exists()) return Outcome.Ok(emptyList())
        return Lockfile
            .read(path)
            .mapError<Lockfile, LockfileError, String> { "rloaded/lockfile.json cannot be used: $it" }
            .flatMap { lockfile ->
                installer
                    .install(lockfile.entries().filter { it.side != Side.CLIENT }) { _, _, _ -> }
                    .mapError<List<InstalledMod>, InstallError, String> { "the mods of rloaded/lockfile.json cannot be obtained: $it" }
                    .map { mods -> mods.map { it.jar } }
            }
    }
}
