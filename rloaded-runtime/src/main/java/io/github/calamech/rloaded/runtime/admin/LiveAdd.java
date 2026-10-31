package io.github.calamech.rloaded.runtime.admin;

import io.github.calamech.rloaded.admin.LiveApply;
import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.AgentBridge;
import io.github.calamech.rloaded.runtime.engine.BlockEntityCycling;
import io.github.calamech.rloaded.runtime.engine.DataReload;
import io.github.calamech.rloaded.runtime.engine.EntityCycling;
import io.github.calamech.rloaded.runtime.engine.HotAdd;
import io.github.calamech.rloaded.runtime.engine.HotAddRefusal;
import io.github.calamech.rloaded.runtime.join.JoinServer;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import kotlin.Unit;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds mods to a server while players are connected. {@link #apply} is the point of no return: it
 * freezes ticks, saves, loads the mods, reloads the data packs and rebuilds the loaded entities and
 * block entities. Once the lockfile records the mods, {@link #rollout} sends every player through
 * the configuration phase, where they install the mods, and lets ticks run again when all are back
 * or gone.
 *
 * <p>Runs one add at a time, as the admission service does. A failure after the mods started
 * loading leaves the game in a state only a restart cleans: the server stops and asks the bootstrap
 * to start it again, without the mods, as the lockfile does not list them.
 */
final class LiveAdd implements LiveApply {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");

  private final MinecraftServer server;
  // The state below belongs to the server thread. Ticks are frozen from the load to the end of the
  // rollout.
  private boolean frozenBefore;
  private boolean frozen;
  private boolean rollingOut;
  private final Set<UUID> waiting = new HashSet<>();
  private final Set<UUID> restarted = new HashSet<>();

  LiveAdd(MinecraftServer server) {
    this.server = server;
  }

  @Override
  public CompletableFuture<Outcome<Unit, String>> apply(List<? extends Path> jars) {
    List<Path> loading = List.copyOf(jars);
    return CompletableFuture.supplyAsync(() -> start(loading), server)
        .thenCompose(
            refusal -> {
              if (refusal.isPresent()) {
                return CompletableFuture.completedFuture(
                    LiveApply.Companion.notLoaded(refusal.get()));
              }
              return loading.isEmpty()
                  ? CompletableFuture.completedFuture(LiveApply.Companion.getLOADED())
                  : reload();
            });
  }

  private Optional<String> start(List<Path> jars) {
    if (frozen) {
      return Optional.of("players are still installing the previous add; try again when it ends");
    }
    return jars.isEmpty() ? Optional.empty() : load(jars);
  }

  // Returns why the mods were not loaded. Before HotAdd registers anything, a refusal changes
  // nothing and ticks run again at once; a failure after that restarts the server.
  private Optional<String> load(List<Path> jars) {
    freeze();
    server.saveEverything(true, false, true);
    try {
      List<String> loaded = HotAdd.load(jars, AgentBridge.instrumentation().orElseThrow());
      LOGGER.info("Loaded {} into the running server", loaded);
      return Optional.empty();
    } catch (HotAddRefusal e) {
      thaw();
      return Optional.of(e.getMessage());
    } catch (RuntimeException e) {
      restart("loading the mods failed", e);
      return Optional.of("loading them threw " + e + "; the server restarts without them");
    }
  }

  private CompletableFuture<Outcome<Unit, String>> reload() {
    return DataReload.run(server)
        .thenApply(
            success -> {
              if (!success) {
                restart("reloading the data packs after the add failed", null);
                return LiveApply.Companion.notLoaded(
                    "reloading the data packs failed; the server restarts");
              }
              for (ServerLevel level : server.getAllLevels()) {
                EntityCycling.cycle(level);
                BlockEntityCycling.Result result = BlockEntityCycling.cycle(level);
                if (!result.notRebuilt().isEmpty()) {
                  LOGGER.warn(
                      "Block entities at {} keep their old objects in {}",
                      result.notRebuilt(),
                      level.dimension().identifier());
                }
              }
              return LiveApply.Companion.getLOADED();
            });
  }

  /**
   * Sends the players to the configuration phase to install the mods of {@code lockfile}, and lets
   * ticks run again once they are all back in the world or gone. Runs on the server thread.
   */
  void rollout(Lockfile lockfile) {
    JoinServer.update(lockfile);
    if (!frozen) {
      freeze();
    }
    List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
    waiting.clear();
    restarted.clear();
    rollingOut = true;
    for (ServerPlayer player : players) {
      waiting.add(player.getUUID());
      player.connection.switchToConfig();
    }
    LOGGER.info("Installing the new mods on {} players", players.size());
    tick();
  }

  /** Moves the rollout on; the server calls it every tick. */
  void tick() {
    if (!rollingOut) {
      return;
    }
    Set<UUID> connected = new HashSet<>();
    for (Connection connection : server.getConnection().getConnections()) {
      PacketListener listener = connection.getPacketListener();
      if (listener instanceof ServerConfigurationPacketListenerImpl configuring) {
        UUID id = configuring.getOwner().id();
        connected.add(id);
        // The configuration starts over, so the install task and Fabric's registry sync run.
        if (waiting.contains(id) && restarted.add(id)) {
          configuring.startConfiguration();
        }
      } else if (listener instanceof ServerGamePacketListenerImpl playing) {
        UUID id = playing.getPlayer().getUUID();
        connected.add(id);
        if (restarted.contains(id) && server.getPlayerList().getPlayer(id) != null) {
          waiting.remove(id);
        }
      }
    }
    waiting.retainAll(connected);
    if (waiting.isEmpty()) {
      rollingOut = false;
      thaw();
      LOGGER.info("Every player has the new mods");
    }
  }

  private void freeze() {
    frozenBefore = server.tickRateManager().isFrozen();
    server.tickRateManager().setFrozen(true);
    frozen = true;
  }

  private void thaw() {
    server.tickRateManager().setFrozen(frozenBefore);
    frozen = false;
  }

  private void restart(String what, Throwable cause) {
    LOGGER.error("{}; the server restarts without the new mods", what, cause);
    ServerRestart.request(server);
  }
}
