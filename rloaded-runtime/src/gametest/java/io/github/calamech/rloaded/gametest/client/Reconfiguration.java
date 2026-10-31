package io.github.calamech.rloaded.gametest.client;

import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

/**
 * Sends the only player back to the configuration phase and into the world again, the way an add
 * does: the configuration starts over, so Fabric's tasks and registry sync run again and the data
 * pack registries are sent again.
 */
final class Reconfiguration {
  private Reconfiguration() {}

  /** Runs one reconfiguration and waits until the client has a new level. */
  // A new level is a new object: identity is what the wait checks.
  @SuppressWarnings("ReferenceEquality")
  static void cycle(ClientGameTestContext context, TestDedicatedServerContext server) {
    ClientLevel levelBefore = context.computeOnClient(client -> client.level);
    server.runOnServer(running -> onlyPlayer(running).connection.switchToConfig());
    server.waitFor(running -> configuringPlayer(running).isPresent());
    server.runOnServer(running -> configuringPlayer(running).orElseThrow().startConfiguration());
    context.waitFor(
        client -> client.level != null && client.level != levelBefore && client.player != null);
  }

  static ServerPlayer onlyPlayer(MinecraftServer server) {
    List<ServerPlayer> players = server.getPlayerList().getPlayers();
    if (players.size() != 1) {
      throw new AssertionError("expected one player, found " + players.size());
    }
    return players.getFirst();
  }

  private static Optional<ServerConfigurationPacketListenerImpl> configuringPlayer(
      MinecraftServer server) {
    return server.getConnection().getConnections().stream()
        .map(connection -> connection.getPacketListener())
        .filter(ServerConfigurationPacketListenerImpl.class::isInstance)
        .map(ServerConfigurationPacketListenerImpl.class::cast)
        .findFirst();
  }
}
