package io.github.calamech.rloaded.runtime.admin;

import io.github.calamech.rloaded.core.runtime.GameExit;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Stops the server and asks the bootstrap to start it again, with {@link GameExit#RESTART}. The
 * game saves and stops as with {@code /stop}; the exit comes once the server thread ended, because
 * the game's own shutdown hook waits for that thread.
 */
final class ServerRestart {
  private static volatile boolean requested;

  private ServerRestart() {}

  /** Registers the exit after the server stops; the server calls it once. */
  static void register() {
    ServerLifecycleEvents.SERVER_STOPPED.register(
        server -> {
          if (requested) {
            Thread serverThread = server.getRunningThread();
            Thread.ofPlatform()
                .name("Rloaded restart")
                .start(
                    () -> {
                      try {
                        serverThread.join();
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      System.exit(GameExit.RESTART);
                    });
          }
        });
  }

  /** Stops {@code server} and has the bootstrap start it again. */
  static void request(MinecraftServer server) {
    requested = true;
    server.halt(false);
  }
}
