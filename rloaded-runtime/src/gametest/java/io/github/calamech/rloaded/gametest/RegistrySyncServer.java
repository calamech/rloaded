package io.github.calamech.rloaded.gametest;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

/**
 * The server side of the registry sync check, active when the harness starts the server for it.
 *
 * <p>Once every player registered the check's blocks on its side, the server registers them too, as
 * an add would, and sends every player through the configuration phase again so Fabric can sync the
 * raw ids. The harness stops the server once the clients wrote their ids.
 */
public final class RegistrySyncServer implements DedicatedServerModInitializer {
  private static final String PLAYERS_PROPERTY = "rloaded.registry-sync.players";

  @Override
  public void onInitializeServer() {
    RegistrySyncCheck.output()
        .ifPresent(
            output -> {
              Integer players = Integer.getInteger(PLAYERS_PROPERTY);
              if (players == null) {
                throw new IllegalStateException("run with the " + PLAYERS_PROPERTY + " property");
              }
              Driver driver = new Driver(players, output);
              ServerPlayNetworking.registerGlobalReceiver(
                  RegistrySyncCheck.BlocksRegistered.TYPE,
                  (payload, context) -> driver.registered.add(context.player().getUUID()));
              ServerTickEvents.END_SERVER_TICK.register(driver::tick);
            });
  }

  private static final class Driver {
    private final int players;
    private final Path output;
    private final Set<UUID> registered = ConcurrentHashMap.newKeySet();
    private final Set<UUID> reconfigured = ConcurrentHashMap.newKeySet();
    private boolean added;

    Driver(int players, Path output) {
      this.players = players;
      this.output = output;
    }

    void tick(MinecraftServer server) {
      if (!added && registered.size() == players) {
        added = true;
        RegistrySyncCheck.registerBlocks();
        RegistrySyncCheck.writeIds(output.resolve("server.txt"));
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
          player.connection.switchToConfig();
        }
      }
      if (added) {
        for (Connection connection : server.getConnection().getConnections()) {
          if (connection.getPacketListener()
                  instanceof ServerConfigurationPacketListenerImpl listener
              && reconfigured.add(listener.getOwner().id())) {
            restartConfiguration(listener);
          }
        }
      }
    }

    // Fabric runs its configuration events, and with them the registry sync, from
    // startConfiguration; returnToWorld alone, as the debug unconfig command does, skips them.
    private static void restartConfiguration(ServerConfigurationPacketListenerImpl listener) {
      listener.startConfiguration();
    }
  }
}
