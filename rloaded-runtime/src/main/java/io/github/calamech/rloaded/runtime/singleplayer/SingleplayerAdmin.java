package io.github.calamech.rloaded.runtime.singleplayer;

import io.github.calamech.rloaded.admin.AddTarget;
import io.github.calamech.rloaded.admin.AddedMod;
import io.github.calamech.rloaded.admin.AdmissionService;
import io.github.calamech.rloaded.admin.CompatDatabase;
import io.github.calamech.rloaded.admin.LiveGame;
import io.github.calamech.rloaded.admin.ReputationRules;
import io.github.calamech.rloaded.admin.staging.Staging;
import io.github.calamech.rloaded.core.download.ArtifactStore;
import io.github.calamech.rloaded.core.download.RetryPolicy;
import io.github.calamech.rloaded.modrinth.ModInstaller;
import io.github.calamech.rloaded.protocol.AddProblem;
import io.github.calamech.rloaded.protocol.ModEntry;
import io.github.calamech.rloaded.runtime.LoadedMods;
import io.github.calamech.rloaded.runtime.ModrinthAccess;
import io.github.calamech.rloaded.runtime.ServerLockfile;
import io.github.calamech.rloaded.runtime.admin.Admissions;
import io.github.calamech.rloaded.runtime.admin.LiveAdd;
import io.github.calamech.rloaded.runtime.admin.ModsChannel;
import io.github.calamech.rloaded.runtime.join.client.GameRelaunch;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the Mods screen may do in a singleplayer world: the world's integrated server admits mods as
 * a dedicated server does, without a staging server, and records them in the world's own lockfile.
 * Only the world's owner may add, and not while the world is open to other players on the local
 * network.
 */
public final class SingleplayerAdmin implements ModsChannel.Target {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");
  private static final String MOD_STORE_PROPERTY = "rloaded.mod-store";
  // The open world's: set when its server started, cleared when it stops.
  private static volatile SingleplayerAdmin open;

  private final IntegratedServer server;
  private final IntegratedServerHost host;
  private final LiveAdd live;
  private final AdmissionService service;

  private SingleplayerAdmin(
      IntegratedServer server, ModrinthAccess access, Path modStore, CompatDatabase compat) {
    this.server = server;
    this.host = new IntegratedServerHost(Minecraft.getInstance());
    this.live = new LiveAdd(server, host);
    ReputationRules rules =
        new ReputationRules(
            Duration.ofHours(ReputationRules.DEFAULT_VERSION_AGE_HOURS),
            ReputationRules.DEFAULT_PROJECT_DOWNLOADS);
    this.service =
        Admissions.service(
            access,
            modStore,
            rules,
            () -> compat,
            Staging.Companion.getOFF(),
            new AddTarget(ServerLockfile.of(server), live, LiveGame.SINGLEPLAYER));
  }

  /** Serves the Mods screen of singleplayer worlds; the client calls it once. */
  public static void register() {
    String modStore = System.getProperty(MOD_STORE_PROPERTY);
    if (modStore == null) {
      LOGGER.info("Started without the launcher: mods cannot be added to singleplayer worlds");
      return;
    }
    ModrinthAccess access = ModrinthAccess.open();
    CompatDatabase compat = Admissions.bundledCompatDatabase();
    IntegratedServerHost.register();
    WorldGate.register(
        new ModInstaller(
            access.modrinth(),
            new ArtifactStore(
                Path.of(modStore),
                access.http(),
                new RetryPolicy(),
                ArtifactStore.DEFAULT_MAX_UNSIZED_BYTES),
            ModInstaller.DEFAULT_PARALLELISM));
    ServerLifecycleEvents.SERVER_STARTED.register(
        server -> {
          if (server instanceof IntegratedServer integrated) {
            open = new SingleplayerAdmin(integrated, access, Path.of(modStore), compat);
            ModsChannel.serve(open);
          }
        });
    ServerLifecycleEvents.SERVER_STOPPING.register(
        server -> {
          SingleplayerAdmin closing = open;
          if (closing != null && closing.server.equals(server)) {
            open = null;
            closing.host.worldClosing();
            ModsChannel.stop(server);
          }
        });
  }

  @Override
  public MinecraftServer server() {
    return server;
  }

  @Override
  public AdmissionService service() {
    return service;
  }

  @Override
  public LiveAdd live() {
    return live;
  }

  @Override
  public boolean allows(ServerPlayer player) {
    return server.isSingleplayerOwner(player.nameAndId());
  }

  @Override
  public Optional<ModsChannel.Refusal> blocked() {
    if (server.isPublished()) {
      return Optional.of(
          new ModsChannel.Refusal(
              AddProblem.SHARED_WORLD, "the world is open to the local network"));
    }
    return Optional.empty();
  }

  @Override
  public String addedBy(ServerPlayer player) {
    return "owner";
  }

  @Override
  public void added(List<AddedMod> mods) {
    for (AddedMod mod : mods) {
      ModEntry entry = mod.getEntry();
      LoadedMods.record(entry.getProjectId(), entry.getVersionId());
    }
  }

  @Override
  public void restart() {
    GameRelaunch.request(Minecraft.getInstance());
  }
}
