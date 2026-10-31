package io.github.calamech.rloaded.runtime;

import io.github.calamech.rloaded.admin.LockfileLocation;
import io.github.calamech.rloaded.core.lockfile.BaseSetEntry;
import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.core.runtime.RuntimePins;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Where a server keeps its lockfile, and what a server without one runs. A dedicated server has one
 * lockfile for its worlds; a singleplayer world has its own.
 */
public final class ServerLockfile {
  private ServerLockfile() {}

  /**
   * The lockfile of {@code server}: in {@code rloaded/} of the server's folder for a dedicated
   * server, of the world's folder for a singleplayer world.
   */
  public static LockfileLocation of(MinecraftServer server) {
    Path folder =
        server.isDedicatedServer()
            ? server.getServerDirectory()
            : server.getWorldPath(LevelResource.ROOT).normalize();
    return new LockfileLocation(folder.resolve("rloaded").resolve("lockfile.json"), whenMissing());
  }

  // A server without a lockfile runs the base set the bootstrap installs, and no other mod.
  private static Lockfile whenMissing() {
    List<BaseSetEntry> baseSet =
        RuntimePins.Companion.getCURRENT().getBaseSet().stream()
            .map(
                mod ->
                    new BaseSetEntry(
                        mod.getProjectId(), mod.getVersionId(), mod.getArtifact().getChecksum()))
            .toList();
    String rloaded =
        FabricLoader.getInstance()
            .getModContainer("rloaded")
            .orElseThrow()
            .getMetadata()
            .getVersion()
            .getFriendlyString();
    return new Lockfile(
        SharedConstants.getCurrentVersion().name(), rloaded, baseSet, List.of(), List.of());
  }
}
