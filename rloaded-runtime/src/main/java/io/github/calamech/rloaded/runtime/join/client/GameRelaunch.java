package io.github.calamech.rloaded.runtime.join.client;

import io.github.calamech.rloaded.core.runtime.GameExit;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stops the game and asks the launcher to start it again, with {@link GameExit#RESTART}; the
 * launcher then joins the same server. The game closes as when the player quits; the exit comes
 * once the game thread ended, after the game saved its options and released its window.
 */
public final class GameRelaunch {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");

  private GameRelaunch() {}

  /** Stops {@code client} and has the launcher start it again. */
  public static void request(Minecraft client) {
    client.execute(
        () -> {
          Thread gameThread = Thread.currentThread();
          LOGGER.info("The game restarts to join the server again with a clean state");
          client.stop();
          Thread.ofPlatform()
              .name("Rloaded relaunch")
              .start(
                  () -> {
                    try {
                      gameThread.join();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    System.exit(GameExit.RESTART);
                  });
        });
  }
}
