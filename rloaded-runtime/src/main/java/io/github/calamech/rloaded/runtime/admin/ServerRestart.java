package io.github.calamech.rloaded.runtime.admin;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Stops the server and asks the bootstrap to start it again, with exit code 75. The game saves and
 * stops as with {@code /stop}; the exit comes once the server thread ended, because the game's own
 * shutdown hook waits for that thread.
 */
final class ServerRestart {
  /** The exit code the bootstrap reads as a request to start the server again. */
  static final int EXIT_CODE = 75;

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
                      System.exit(EXIT_CODE);
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
