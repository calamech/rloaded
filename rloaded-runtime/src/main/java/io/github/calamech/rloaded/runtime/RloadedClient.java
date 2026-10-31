package io.github.calamech.rloaded.runtime;

import io.github.calamech.rloaded.runtime.join.client.JoinClient;
import io.github.calamech.rloaded.runtime.mods.ModsClient;
import io.github.calamech.rloaded.runtime.singleplayer.SingleplayerAdmin;
import net.fabricmc.api.ClientModInitializer;

/** Client entry point of the runtime mod. */
public final class RloadedClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    JoinClient.register();
    SingleplayerAdmin.register();
    ModsClient.register();
  }
}
