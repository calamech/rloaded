package io.github.calamech.rloaded.testmods.failsonclientonce;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;

/**
 * A test mod that fails as it starts on a client, once: when the file named by {@code
 * rloaded.test.fail-once} exists, it deletes it and throws. Servers run it without trouble, so only
 * a player's game can find the failure, and a fresh game started afterwards loads it.
 */
public final class FailsOnClientOnce implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    String marker = System.getProperty("rloaded.test.fail-once");
    try {
      if (marker != null && Files.deleteIfExists(Path.of(marker))) {
        throw new IllegalStateException("fails-on-client-once fails as it starts, on purpose");
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
