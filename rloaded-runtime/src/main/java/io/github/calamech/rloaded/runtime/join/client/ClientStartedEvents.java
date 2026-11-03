package io.github.calamech.rloaded.runtime.join.client;

import io.github.calamech.rloaded.runtime.engine.StartedEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;

/** The events that fired as the game started, which an add replays for its mods on a client. */
public final class ClientStartedEvents {
  private ClientStartedEvents() {}

  /**
   * The started events of a client that is running.
   *
   * @param client the running game
   * @return its started events
   */
  public static StartedEvents of(Minecraft client) {
    return StartedEvents.of(
        "the game started",
        ClientLifecycleEvents.CLIENT_STARTED,
        listener -> listener.onClientStarted(client));
  }
}
