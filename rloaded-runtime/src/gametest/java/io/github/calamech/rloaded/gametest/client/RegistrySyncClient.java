package io.github.calamech.rloaded.gametest.client;

import io.github.calamech.rloaded.gametest.RegistrySyncCheck;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client side of the registry sync check, active when the harness starts the client for it.
 *
 * <p>On joining, the client registers the check's blocks in its own order, writes its raw ids, and
 * tells the server. When the server lets it back into the world after the configuration phase, it
 * writes its raw ids again and quits. It also quits when it gets disconnected.
 */
final class RegistrySyncClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(RegistrySyncClient.class);

  private RegistrySyncClient() {}

  static void register() {
    RegistrySyncCheck.output().ifPresent(RegistrySyncClient::register);
  }

  private static void register(Path output) {
    AtomicBoolean registered = new AtomicBoolean();
    ClientPlayConnectionEvents.JOIN.register(
        (listener, sender, client) -> {
          // Fabric only logs what a join listener throws: quit instead, so the harness sees the
          // missing ids at once rather than waiting for a server that waits for this client.
          try {
            if (registered.compareAndSet(false, true)) {
              RegistrySyncCheck.registerBlocks();
              RegistrySyncCheck.writeIds(output.resolve("before.txt"));
              sender.sendPacket(new RegistrySyncCheck.BlocksRegistered());
            } else {
              RegistrySyncCheck.writeIds(output.resolve("after.txt"));
              client.stop();
            }
          } catch (RuntimeException e) {
            LOGGER.error("Registry sync check failed on the client", e);
            client.stop();
          }
        });
    // A refused or dropped connection leaves the client on this screen for good.
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          if (client.gui.screen() instanceof DisconnectedScreen) {
            LOGGER.error("Registry sync check failed: the client was disconnected");
            client.stop();
          }
        });
  }
}
