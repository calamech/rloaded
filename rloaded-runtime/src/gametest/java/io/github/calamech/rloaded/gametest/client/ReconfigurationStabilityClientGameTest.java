package io.github.calamech.rloaded.gametest.client;

import io.github.calamech.rloaded.gametest.PingTraffic;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Checks that a player stays connected through many reconfigurations in a row while mods send
 * custom packets both ways every tick.
 */
public final class ReconfigurationStabilityClientGameTest implements FabricClientGameTest {
  private static final int CYCLES = 100;

  @Override
  public void runTest(ClientGameTestContext context) {
    try (TestDedicatedServerContext server = context.worldBuilder().createServer();
        TestDedicatedServerConnection connection = server.connect()) {
      connection.waitForChunksDownload();
      context.waitFor(client -> PingTraffic.RECEIVED_BY_CLIENT.get() > 0 && PingTraffic.RECEIVED_BY_SERVER.get() > 0);
      ClientPacketListener listenerBefore = context.computeOnClient(client -> client.getConnection());

      for (int cycle = 1; cycle <= CYCLES; cycle++) {
        Reconfiguration.cycle(context, server);
        long clientCount = PingTraffic.RECEIVED_BY_CLIENT.get();
        long serverCount = PingTraffic.RECEIVED_BY_SERVER.get();
        context.waitFor(client -> PingTraffic.RECEIVED_BY_CLIENT.get() > clientCount && PingTraffic.RECEIVED_BY_SERVER.get() > serverCount);
        boolean connected = context.computeOnClient(client -> client.getConnection() != null && client.getConnection().getConnection().isConnected());
        if (!connected) {
          throw new AssertionError("the client lost its connection at reconfiguration " + cycle);
        }
      }

      boolean sameConnection =
          context.computeOnClient(client -> client.getConnection().getConnection() == listenerBefore.getConnection());
      if (!sameConnection) {
        throw new AssertionError("the client reconnected instead of staying on its connection");
      }
    }
    ClientTestReport.passed(CYCLES + " reconfigurations with custom packets both ways");
  }
}
