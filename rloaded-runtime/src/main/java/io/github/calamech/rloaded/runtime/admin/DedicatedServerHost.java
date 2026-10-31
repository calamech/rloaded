package io.github.calamech.rloaded.runtime.admin;

import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.runtime.AgentBridge;
import io.github.calamech.rloaded.runtime.engine.HotAdd;
import io.github.calamech.rloaded.runtime.engine.HotAddRefusal;
import io.github.calamech.rloaded.runtime.join.JoinServer;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds to a dedicated server: its thread loads the mods, players install them through the join
 * task, and the bootstrap starts the server again after a failure.
 */
final class DedicatedServerHost implements LiveAdd.Host {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");

  private final MinecraftServer server;

  DedicatedServerHost(MinecraftServer server) {
    this.server = server;
  }

  // A failure after the mods started loading is thrown here, on the server thread, and completes
  // the future with it.
  @Override
  public CompletableFuture<Optional<String>> load(List<Path> jars) {
    try {
      List<String> loaded = HotAdd.load(jars, AgentBridge.instrumentation().orElseThrow());
      LOGGER.info("Loaded {} into the running server", loaded);
      return CompletableFuture.completedFuture(Optional.empty());
    } catch (HotAddRefusal e) {
      return CompletableFuture.completedFuture(Optional.of(e.getMessage()));
    } catch (RuntimeException | AssertionError e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  @Override
  public void tick() {}

  @Override
  public CompletableFuture<Void> beforeRollout(Lockfile lockfile) {
    JoinServer.update(lockfile);
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void restart() {
    ServerRestart.request(server);
  }
}
