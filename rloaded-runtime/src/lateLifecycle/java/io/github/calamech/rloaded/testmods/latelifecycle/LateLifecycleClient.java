package io.github.calamech.rloaded.testmods.latelifecycle;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/** Sets up its client state when the game started, and uses it at each client tick. */
public final class LateLifecycleClient implements ClientModInitializer {
  private static volatile Minecraft clientState;

  @Override
  public void onInitializeClient() {
    ClientLifecycleEvents.CLIENT_STARTED.register(
        client -> {
          clientState = client;
          LateLifecycle.note("client started");
        });
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          if (clientState == null) {
            throw new IllegalStateException("late-lifecycle was never told the game started");
          }
        });
  }
}
