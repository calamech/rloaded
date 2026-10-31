package io.github.calamech.rloaded.runtime.admin;

import io.github.calamech.rloaded.admin.LiveApply;
import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.engine.BlockEntityCycling;
import io.github.calamech.rloaded.runtime.engine.DataReload;
import io.github.calamech.rloaded.runtime.engine.EntityCycling;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
 * Adds mods to a running server while players are connected: a dedicated server, or the server of
 * the world a game has open. {@link #apply} is the point of no return: it freezes ticks, saves,
 * loads the mods, reloads the data packs and rebuilds the loaded entities and block entities. Once
 * the lockfile records the mods, {@link #rollout} sends every player through the configuration
 * phase, which rebuilds their games' worlds and installs the mods on clients that miss them, and
 * lets ticks run again when all are back or gone.
 *
 * <p>Runs one add at a time, as the admission service does. A failure after the mods started
 * loading leaves the game in a state only a restart cleans: the game stops and asks to be started
 * again, without the mods, as the lockfile does not list them.
 */
public final class LiveAdd implements LiveApply {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");
  // The add of the server this game runs; a game runs one server at a time.
  private static volatile LiveAdd active;

  private final MinecraftServer server;
  private final Host host;
  // The state below belongs to the server thread. Ticks are frozen from the load to the end of the
  // rollout.
  private boolean frozenBefore;
  private boolean frozen;
  private boolean rollingOut;
  private CompletableFuture<Void> rolledOut;
  private final Set<UUID> waiting = new HashSet<>();
  private final Set<UUID> restarted = new HashSet<>();

  /** What differs between a dedicated server and the server of a singleplayer world. */
  public interface Host {
    /**
     * Loads {@code jars} into the game. Starts on the server thread, ticks frozen.
     *
     * @return completes on the server thread with why nothing was loaded, or empty once the mods
     *     are loaded; completes with a {@link RuntimeException} or {@link AssertionError} if
     *     loading failed after it started
     */
    CompletableFuture<Optional<String>> load(List<Path> jars);

    /** Moves a load on; runs on the server thread every tick, also while the server pauses. */
    void tick();

    /** Gets the game ready for players to install the mods of {@code lockfile}. */
    CompletableFuture<Void> beforeRollout(Lockfile lockfile);

    /** Stops the game and asks to be started again. */
    void restart();
  }

  /** Adds mods to {@code server}, which this add becomes the one of. */
  public LiveAdd(MinecraftServer server, Host host) {
    this.server = server;
    this.host = host;
    active = this;
  }

  /**
   * Moves the rollout of {@code server} on. The server calls it every tick, and while a
   * singleplayer game is paused, which it is while its only player is in the configuration phase.
   */
  public static void connectionsTicked(MinecraftServer server) {
    LiveAdd live = active;
    if (live != null && live.server.equals(server)) {
      live.host.tick();
      live.tick();
    }
  }

  @Override
  public CompletableFuture<Outcome<Unit, String>> apply(List<? extends Path> jars) {
    List<Path> loading = List.copyOf(jars);
    return CompletableFuture.completedFuture(loading)
        .thenComposeAsync(this::start, server)
        .thenComposeAsync(
            refusal -> {
              if (refusal.isPresent()) {
                return CompletableFuture.completedFuture(
                    LiveApply.Companion.notLoaded(refusal.get()));
              }
              return loading.isEmpty()
                  ? CompletableFuture.completedFuture(LiveApply.Companion.getLOADED())
                  : reload();
            },
            server);
  }

  private CompletableFuture<Optional<String>> start(List<Path> jars) {
    if (frozen) {
      return CompletableFuture.completedFuture(
          Optional.of("players are still installing the previous add; try again when it ends"));
    }
    return jars.isEmpty() ? CompletableFuture.completedFuture(Optional.empty()) : load(jars);
  }

  // Completes with why the mods were not loaded. Before they start loading, a refusal changes
  // nothing and ticks run again at once; a failure after that restarts the game.
  private CompletableFuture<Optional<String>> load(List<Path> jars) {
    freeze();
    server.saveEverything(true, false, true);
    return host.load(jars)
        .handle(
            (refusal, error) -> {
              if (error != null) {
                // The game checks its registries with assertions: a mod that breaks them fails
                // like one that throws.
                Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                restart("loading the mods failed", cause);
                return Optional.of(
                    "loading them threw " + cause + "; the game restarts without them");
              }
              if (refusal.isPresent()) {
                thaw();
              }
              return refusal;
            });
  }

  private CompletableFuture<Outcome<Unit, String>> reload() {
    return DataReload.run(server)
        .thenApply(
            success -> {
              if (!success) {
                restart("reloading the data packs after the add failed", null);
                return LiveApply.Companion.notLoaded(
                    "reloading the data packs failed; the game restarts");
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
   *
   * @return completes on the server thread once every player is back or gone
   */
  public CompletableFuture<Void> rollout(Lockfile lockfile) {
    if (!frozen) {
      freeze();
    }
    CompletableFuture<Void> done = new CompletableFuture<>();
    CompletableFuture<?> _ =
        host.beforeRollout(lockfile)
            .whenComplete(
                (ready, error) ->
                    server.execute(
                        () -> {
                          if (error != null) {
                            LOGGER.error("The game could not get ready for the new mods", error);
                          }
                          sendToConfiguration(done);
                        }));
    return done;
  }

  private void sendToConfiguration(CompletableFuture<Void> done) {
    List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
    waiting.clear();
    restarted.clear();
    rolledOut = done;
    rollingOut = true;
    for (ServerPlayer player : players) {
      waiting.add(player.getUUID());
      player.connection.switchToConfig();
    }
    LOGGER.info("Installing the new mods on {} players", players.size());
    tick();
  }

  private void tick() {
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
      rolledOut.complete(null);
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
    LOGGER.error("{}; the game restarts without the new mods", what, cause);
    host.restart();
  }
}
