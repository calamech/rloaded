package io.github.calamech.rloaded.testmods.latelifecycle;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Sets up its server state when the server started, like a minimap that keeps per-server data, and
 * uses it at each tick of each player (mixin/ServerPlayerMixin). Added to a running server, it only
 * works if it is told the server started. Each event it receives is noted in the system property
 * {@value #CALLS}, so checks can tell what it was told and how often.
 */
public final class LateLifecycle implements ModInitializer {
  /** The events received, joined with {@code ;}. */
  public static final String CALLS = "rloaded.late-lifecycle.calls";

  private static volatile MinecraftServer serverState;

  @Override
  public void onInitialize() {
    ServerLifecycleEvents.SERVER_STARTING.register(server -> note("server starting"));
    ServerLevelEvents.LOAD.register(
        (server, level) -> note("level " + level.dimension().identifier()));
    ServerLifecycleEvents.SERVER_STARTED.register(
        server -> {
          serverState = server;
          note("server started");
        });
  }

  /**
   * The server it was told started.
   *
   * @return the server, or null when it was never told
   */
  public static MinecraftServer serverState() {
    return serverState;
  }

  static synchronized void note(String call) {
    String before = System.getProperty(CALLS, "");
    System.setProperty(CALLS, before.isEmpty() ? call : before + ";" + call);
  }
}
