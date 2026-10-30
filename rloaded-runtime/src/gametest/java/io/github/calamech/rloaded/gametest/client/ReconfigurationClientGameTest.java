package io.github.calamech.rloaded.gametest.client;

import java.util.Optional;
import java.util.stream.StreamSupport;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * Checks that the client rebuilds the entities around the player when the server sends it back to
 * the configuration phase, as it does to install mods while players are connected.
 */
public final class ReconfigurationClientGameTest implements FabricClientGameTest {
  @Override
  public void runTest(ClientGameTestContext context) {
    try (TestDedicatedServerContext server = context.worldBuilder().createServer();
        TestDedicatedServerConnection connection = server.connect()) {
      connection.waitForChunksRender();
      server.runOnServer(ReconfigurationClientGameTest::spawnArmorStandNextToPlayer);
      context.waitFor(client -> armorStand(client).isPresent());
      ClientLevel levelBefore = context.computeOnClient(client -> client.level);
      Entity standBefore = context.computeOnClient(client -> armorStand(client).orElseThrow());

      Reconfiguration.cycle(context, server);
      context.waitFor(client -> armorStand(client).isPresent());

      Entity standAfter = context.computeOnClient(client -> armorStand(client).orElseThrow());
      if (standAfter == standBefore) {
        throw new AssertionError("the client kept the armor stand object across reconfiguration");
      }
      if (standBefore.level() != levelBefore) {
        throw new AssertionError("the armor stand seen before reconfiguration was not in the client's level");
      }
      if (!standAfter.getUUID().equals(standBefore.getUUID())) {
        throw new AssertionError("the rebuilt armor stand is another entity");
      }
    }
    ClientTestReport.passed("reconfiguration rebuilds client entities");
  }

  private static void spawnArmorStandNextToPlayer(MinecraftServer server) {
    ServerPlayer player = Reconfiguration.onlyPlayer(server);
    ArmorStand stand = new ArmorStand(player.level(), player.getX() + 2, player.getY(), player.getZ());
    if (!player.level().addFreshEntity(stand)) {
      throw new AssertionError("the server refused the armor stand");
    }
  }

  private static Optional<Entity> armorStand(Minecraft client) {
    if (client.level == null) {
      return Optional.empty();
    }
    return StreamSupport.stream(client.level.entitiesForRendering().spliterator(), false)
        .filter(entity -> entity.getType() == EntityTypes.ARMOR_STAND)
        .findFirst();
  }
}
