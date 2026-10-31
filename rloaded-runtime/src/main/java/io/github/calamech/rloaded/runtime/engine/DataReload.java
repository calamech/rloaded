package io.github.calamech.rloaded.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Reloads a server's data packs the way {@code /reload} does, new mods' packs included. That is how
 * the game binds the components of items registered after it started, so an add reloads once its
 * mods are loaded.
 */
public final class DataReload {
  // Reloads waiting for the game to report back; the server thread is the only one to touch it.
  private static final List<CompletableFuture<Boolean>> WAITING = new ArrayList<>();

  private DataReload() {}

  /** Registers the listener that completes the reloads; the server calls it once. */
  public static void register() {
    ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(
        (server, resources, success) -> {
          List<CompletableFuture<Boolean>> done = List.copyOf(WAITING);
          WAITING.clear();
          done.forEach(future -> future.complete(success));
        });
  }

  /**
   * Starts {@code /reload} on {@code server}. Must run on the server thread.
   *
   * @return completes on the server thread with whether the reload succeeded
   */
  public static CompletableFuture<Boolean> run(MinecraftServer server) {
    if (!server.isSameThread()) {
      throw new IllegalStateException("data packs can only be reloaded from the server thread");
    }
    CompletableFuture<Boolean> reloaded = new CompletableFuture<>();
    WAITING.add(reloaded);
    server
        .getCommands()
        .performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "reload");
    return reloaded;
  }
}
