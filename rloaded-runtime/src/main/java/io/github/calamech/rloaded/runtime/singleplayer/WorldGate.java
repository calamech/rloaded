package io.github.calamech.rloaded.runtime.singleplayer;

import io.github.calamech.rloaded.admin.AuditLog;
import io.github.calamech.rloaded.admin.RemovalsAtStart;
import io.github.calamech.rloaded.admin.RemovalsOutcome;
import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.core.lockfile.WorldOpening;
import io.github.calamech.rloaded.modrinth.InstalledMod;
import io.github.calamech.rloaded.modrinth.ModInstaller;
import io.github.calamech.rloaded.protocol.ModEntry;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.AgentBridge;
import io.github.calamech.rloaded.runtime.LoadedMods;
import io.github.calamech.rloaded.runtime.engine.HotAdd;
import io.github.calamech.rloaded.runtime.engine.HotAddRefusal;
import io.github.calamech.rloaded.runtime.engine.LateKeyMappings;
import io.github.calamech.rloaded.runtime.join.client.GameRelaunch;
import io.github.calamech.rloaded.runtime.join.client.JoinClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import kotlin.Unit;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gets the game ready for a singleplayer world before the world loads, from the world's lockfile:
 * installs and loads the mods the game misses, so their blocks and items exist when the world's
 * chunks are read; or, if the game loaded mods the world does not list, starts the game again,
 * which then opens the world from the title screen. A game that loaded mods does not create a world
 * either: it starts again first.
 */
public final class WorldGate {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");
  // Null in a game started without the launcher, which has no store for mods.
  private static volatile ModInstaller installer;
  // The world let through once its mods are loaded. The client thread is the only one to touch it.
  private static String ready;

  private WorldGate() {}

  /** Opens again, at the title screen, the world a restart was for; the client calls it once. */
  static void register(ModInstaller modInstaller) {
    installer = modInstaller;
    ScreenEvents.AFTER_INIT.register(
        (client, screen, width, height) -> {
          Path file = reopenFile(client);
          if (screen instanceof TitleScreen && Files.exists(file)) {
            String levelId = read(file);
            if (levelId == null) {
              return;
            }
            LOGGER.info("Opening {} again after the restart", levelId);
            client.execute(
                () ->
                    client
                        .createWorldOpenFlows()
                        .openWorld(levelId, () -> client.gui.setScreen(new TitleScreen())));
          }
        });
  }

  /**
   * Whether the game opens world {@code levelId} now. When it does not, it opens it later itself,
   * or calls {@code onCancel}. Runs on the client thread.
   */
  public static boolean beforeOpen(String levelId, Runnable onCancel) {
    if (levelId.equals(ready)) {
      ready = null;
      return true;
    }
    Minecraft client = Minecraft.getInstance();
    Path world = client.getLevelSource().getBaseDir().resolve(levelId);
    Path path = world.resolve("rloaded").resolve("lockfile.json");
    applyRemovals(levelId, world, path);
    List<ModEntry> worldMods;
    if (Files.exists(path)) {
      switch (Lockfile.Companion.read(path)) {
        case Outcome.Ok<?> ok ->
            worldMods =
                ((Lockfile) ok.getValue()).getMods().stream().map(mod -> mod.getEntry()).toList();
        case Outcome.Err<?> err -> {
          LOGGER.error("The lockfile of {} cannot be read: {}", levelId, err.getError());
          fail(client, onCancel, Component.translatable("rloaded.world.unreadable"));
          return false;
        }
      }
    } else {
      worldMods = List.of();
    }
    return switch (WorldOpening.Companion.of(worldMods, LoadedMods.snapshot())) {
      case WorldOpening.Open open -> true;
      case WorldOpening.Install install -> {
        install(client, levelId, onCancel, install.getMissing());
        yield false;
      }
      case WorldOpening.Restart restart -> {
        LOGGER.info(
            "{} does not list {}, which this game loaded: the game restarts to open it",
            levelId,
            restart.getExtra());
        write(reopenFile(client), levelId);
        GameRelaunch.request(client);
        yield false;
      }
      default -> throw new IllegalStateException("unknown world opening");
    };
  }

  // The mods the owner marked for removal leave the world before it opens, after a backup of it.
  private static void applyRemovals(String levelId, Path world, Path lockfile) {
    Clock clock = Clock.systemUTC();
    Path data = world.resolve("rloaded");
    RemovalsAtStart removals =
        new RemovalsAtStart(
            lockfile,
            world,
            data.resolve("backups"),
            new AuditLog(
                data.resolve("audit.log"),
                clock,
                error -> {
                  LOGGER.error("The audit log of {} cannot be written: {}", levelId, error);
                  return Unit.INSTANCE;
                }),
            clock,
            RemovalsAtStart.KEEP_BACKUPS);
    switch (removals.apply()) {
      case RemovalsOutcome.Removed removed ->
          LOGGER.info(
              "Removed {} from {}; the world was saved to {}",
              removed.getProjects(),
              levelId,
              removed.getBackup());
      case RemovalsOutcome.Postponed postponed ->
          LOGGER.error(
              "Kept {} in {}, still to be removed at a later opening: {}",
              postponed.getProjects(),
              levelId,
              postponed.getReason());
      default -> {}
    }
  }

  /** Whether the game creates a world now; a game that loaded mods starts again first. */
  public static boolean beforeCreate() {
    if (LoadedMods.snapshot().isEmpty()) {
      return true;
    }
    LOGGER.info("This game loaded mods: it restarts before it creates a world");
    GameRelaunch.request(Minecraft.getInstance());
    return false;
  }

  private static void install(
      Minecraft client, String levelId, Runnable onCancel, List<ModEntry> missing) {
    ModInstaller modInstaller = installer;
    if (modInstaller == null) {
      fail(client, onCancel, Component.translatable("rloaded.world.no_launcher"));
      return;
    }
    LOGGER.info(
        "Installing the mods of {}: {}",
        levelId,
        missing.stream().map(ModEntry::getProjectId).toList());
    client.gui.setScreen(
        new GenericMessageScreen(Component.translatable("rloaded.world.installing")));
    CompletableFuture<?> _ =
        modInstaller
            .installAsync(missing, (entry, done, total) -> {})
            .whenComplete(
                (outcome, error) ->
                    client.execute(
                        () -> {
                          if (error != null || outcome instanceof Outcome.Err<?>) {
                            Object why =
                                error != null ? error : ((Outcome.Err<?>) outcome).getError();
                            LOGGER.error("The mods of {} cannot be installed: {}", levelId, why);
                            fail(
                                client,
                                onCancel,
                                Component.translatable("rloaded.world.not_installed"));
                          } else {
                            load(client, levelId, onCancel, installed(outcome));
                          }
                        }));
  }

  @SuppressWarnings("unchecked")
  private static List<InstalledMod> installed(Outcome<?, ?> outcome) {
    return (List<InstalledMod>) ((Outcome.Ok<?>) outcome).getValue();
  }

  private static void load(
      Minecraft client, String levelId, Runnable onCancel, List<InstalledMod> mods) {
    LateKeyMappings.open();
    try {
      HotAdd.load(
          mods.stream().map(InstalledMod::getJar).toList(),
          AgentBridge.instrumentation().orElseThrow());
    } catch (HotAddRefusal refusal) {
      LOGGER.error("A mod of {} cannot be loaded: {}", levelId, refusal.getMessage());
      fail(client, onCancel, Component.translatable("rloaded.world.not_loaded"));
      return;
    } catch (RuntimeException | AssertionError e) {
      // Some of the mods may have registered content: only a fresh game is clean.
      LOGGER.error("Loading the mods of {} failed; the game restarts", levelId, e);
      GameRelaunch.request(client);
      return;
    } finally {
      LateKeyMappings.close(client);
    }
    mods.forEach(
        mod -> LoadedMods.record(mod.getEntry().getProjectId(), mod.getEntry().getVersionId()));
    JoinClient.loadedInWorld();
    CompletableFuture<?> _ =
        client
            .reloadResourcePacks()
            .whenComplete(
                (reloaded, error) ->
                    client.execute(
                        () -> {
                          ready = levelId;
                          client.createWorldOpenFlows().openWorld(levelId, onCancel);
                        }));
  }

  private static void fail(Minecraft client, Runnable onCancel, Component reason) {
    client.gui.setScreen(
        new AlertScreen(onCancel, Component.translatable("rloaded.world.failed"), reason));
  }

  private static Path reopenFile(Minecraft client) {
    return client.gameDirectory.toPath().resolve("rloaded").resolve("open-world.txt");
  }

  // A file that cannot be read or deleted is left alone: the player opens the world themselves.
  private static String read(Path file) {
    try {
      String levelId = Files.readString(file, StandardCharsets.UTF_8).strip();
      Files.delete(file);
      return levelId;
    } catch (IOException e) {
      LOGGER.error("The world to open after the restart cannot be read from {}", file, e);
      return null;
    }
  }

  // Without the file the game restarts all the same, on the title screen.
  private static void write(Path file, String levelId) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, levelId, StandardCharsets.UTF_8);
    } catch (IOException e) {
      LOGGER.error("The world to open after the restart cannot be written to {}", file, e);
    }
  }
}
