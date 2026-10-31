package io.github.calamech.rloaded.runtime.singleplayer;

import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.core.runtime.Handoff;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.AgentBridge;
import io.github.calamech.rloaded.runtime.admin.LiveAdd;
import io.github.calamech.rloaded.runtime.engine.HotAdd;
import io.github.calamech.rloaded.runtime.engine.HotAddRefusal;
import io.github.calamech.rloaded.runtime.engine.LateKeyMappings;
import io.github.calamech.rloaded.runtime.join.client.GameRelaunch;
import io.github.calamech.rloaded.runtime.join.client.JoinClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds to the world a game has open. The game is both the server and its player's client, so the
 * mods load once, on the client thread, which runs their client entrypoints there as at startup,
 * while the server thread, its ticks frozen, waits for it: they meet through a {@link Handoff} the
 * server parks at each of its ticks and the client offers at each of its own. The client then
 * reloads its resources before its player goes through the configuration phase, which rebuilds its
 * world with the new classes.
 */
final class IntegratedServerHost implements LiveAdd.Host {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");
  // A tick of either thread comes well within this while the game runs.
  private static final Duration MEET_TIMEOUT = Duration.ofSeconds(1);
  // A client thread that does not come for this long is waiting for something else, such as the
  // server stopping as the player leaves.
  private static final Duration START_TIMEOUT = Duration.ofSeconds(30);
  // The load the client offers to run at its ticks; one at a time, as adds are.
  private static volatile Handoff<Loaded> offered;

  private final Minecraft client;
  // The state below belongs to the server thread.
  private Handoff<Loaded> parking;
  private CompletableFuture<Optional<String>> loaded;

  IntegratedServerHost(Minecraft client) {
    this.client = client;
  }

  /**
   * Lets the client thread run the load of an add at each client tick; the client calls it once.
   */
  static void register() {
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          Handoff<Loaded> handoff = offered;
          if (handoff != null && handoff.offer()) {
            offered = null;
          }
        });
  }

  @Override
  public CompletableFuture<Optional<String>> load(List<Path> jars) {
    parking =
        new Handoff<>(
            () -> loadOnClientThread(jars), MEET_TIMEOUT, START_TIMEOUT, System::nanoTime);
    loaded = new CompletableFuture<>();
    offered = parking;
    return loaded;
  }

  /** The ids of the loaded mods, or why none was loaded. */
  private record Loaded(List<String> ids, Optional<String> refusal) {}

  // A refusal comes back as a value; a failure after the mods started loading is thrown.
  private Loaded loadOnClientThread(List<Path> jars) {
    LateKeyMappings.open();
    try {
      return new Loaded(
          HotAdd.load(jars, AgentBridge.instrumentation().orElseThrow()), Optional.empty());
    } catch (HotAddRefusal refusal) {
      return new Loaded(List.of(), Optional.of(refusal.getMessage()));
    } finally {
      LateKeyMappings.close(client);
    }
  }

  @Override
  public void tick() {
    if (parking == null) {
      return;
    }
    switch (parking.park()) {
      case Handoff.Meeting.Ran<?> ran -> finish(ran.getOutcome());
      case Handoff.Meeting.Missed missed -> {}
      case Handoff.Meeting.CalledOff calledOff ->
          end(
              Optional.of(
                  "the game did not start loading the mods within "
                      + START_TIMEOUT.toSeconds()
                      + " seconds; nothing was loaded"));
    }
  }

  /** Calls the load off if it did not start, as the world closes. Runs on the server thread. */
  void worldClosing() {
    if (parking != null && parking.callOff()) {
      end(Optional.of("the world closed before the mods were loaded; nothing was loaded"));
    }
  }

  private void finish(Outcome<?, Throwable> ran) {
    switch (ran) {
      case Outcome.Ok<?> ok -> {
        Loaded loaded = (Loaded) ok.getValue();
        if (loaded.refusal().isEmpty()) {
          LOGGER.info("Loaded {} into the open world", loaded.ids());
          JoinClient.loadedInWorld();
        }
        end(loaded.refusal());
      }
      case Outcome.Err<?> err -> {
        Throwable error = (Throwable) err.getError();
        CompletableFuture<Optional<String>> failed = loaded;
        parking = null;
        failed.completeExceptionally(
            error instanceof RuntimeException || error instanceof AssertionError
                ? error
                : new IllegalStateException("loading the mods failed", error));
      }
    }
  }

  private void end(Optional<String> refusal) {
    CompletableFuture<Optional<String>> done = loaded;
    parking = null;
    done.complete(refusal);
  }

  @Override
  public CompletableFuture<Void> beforeRollout(Lockfile lockfile) {
    CompletableFuture<Void> ready = new CompletableFuture<>();
    client.execute(
        () -> {
          CompletableFuture<?> _ =
              client
                  .reloadResourcePacks()
                  .whenComplete(
                      (reloaded, error) -> {
                        if (error == null) {
                          ready.complete(null);
                        } else {
                          ready.completeExceptionally(error);
                        }
                      });
        });
    return ready;
  }

  @Override
  public void restart() {
    GameRelaunch.request(client);
  }
}
