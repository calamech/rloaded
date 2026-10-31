package io.github.calamech.rloaded.testmods.failsinrunningserver;

import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;

/**
 * A test mod that fails as it starts in a game whose working directory holds the file {@code
 * fail-in-running-server}, and starts fine elsewhere. A check puts that file in the folder of the
 * server it adds the mod to: the staging server, which runs in a folder of its own, accepts the
 * mod, and the running server fails after it started loading it.
 */
public final class FailsInRunningServer implements ModInitializer {
  @Override
  public void onInitialize() {
    if (Files.exists(Path.of("fail-in-running-server"))) {
      throw new IllegalStateException("fails-in-running-server fails as it starts, on purpose");
    }
  }
}
