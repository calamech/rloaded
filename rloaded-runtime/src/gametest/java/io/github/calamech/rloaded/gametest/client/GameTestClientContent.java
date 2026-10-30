package io.github.calamech.rloaded.gametest.client;

import io.github.calamech.rloaded.gametest.PingTraffic;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** The client side of {@link PingTraffic}: answers every tick while connected. */
public final class GameTestClientContent implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    ClientPlayNetworking.registerGlobalReceiver(
        PingTraffic.Ping.TYPE, (ping, context) -> PingTraffic.RECEIVED_BY_CLIENT.incrementAndGet());
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          if (client.player != null && ClientPlayNetworking.canSend(PingTraffic.Ping.TYPE)) {
            ClientPlayNetworking.send(new PingTraffic.Ping(client.player.tickCount));
          }
        });
  }
}
