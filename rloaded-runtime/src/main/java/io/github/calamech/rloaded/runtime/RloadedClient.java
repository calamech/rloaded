package io.github.calamech.rloaded.runtime;

import io.github.calamech.rloaded.runtime.join.client.JoinClient;
import net.fabricmc.api.ClientModInitializer;

/** Client entry point of the runtime mod. */
public final class RloadedClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    JoinClient.register();
  }
}
