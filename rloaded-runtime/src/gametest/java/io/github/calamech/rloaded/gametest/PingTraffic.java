package io.github.calamech.rloaded.gametest;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Custom packets sent every tick in both directions, the way content mods keep clients in sync, so
 * reconfiguration is tested with traffic of other mods around it. A careless mod is played too: it
 * keeps every player it saw and sends each a play packet every tick, even in the configuration
 * phase.
 */
public final class PingTraffic {
  /** Packets the server received from clients. */
  public static final AtomicLong RECEIVED_BY_SERVER = new AtomicLong();

  /** Packets the client received from the server. */
  public static final AtomicLong RECEIVED_BY_CLIENT = new AtomicLong();

  // Players the careless mod holds on to; the server thread is the only one to touch it.
  private static final Set<ServerPlayer> REMEMBERED =
      Collections.newSetFromMap(new WeakHashMap<>());

  private PingTraffic() {}

  /** A packet carrying the sender's tick count. */
  public record Ping(int tick) implements CustomPacketPayload {
    /** The payload type, in both directions. */
    public static final Type<Ping> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("rloaded-gametest", "ping"));

    /** Writes the tick count as a variable-length integer. */
    public static final StreamCodec<RegistryFriendlyByteBuf, Ping> CODEC =
        StreamCodec.composite(ByteBufCodecs.VAR_INT, Ping::tick, Ping::new);

    @Override
    public Type<Ping> type() {
      return TYPE;
    }
  }

  static void register() {
    PayloadTypeRegistry.clientboundPlay().register(Ping.TYPE, Ping.CODEC);
    PayloadTypeRegistry.serverboundPlay().register(Ping.TYPE, Ping.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(
        Ping.TYPE, (ping, context) -> RECEIVED_BY_SERVER.incrementAndGet());
    ServerTickEvents.END_SERVER_TICK.register(
        server -> {
          for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, Ping.TYPE)) {
              ServerPlayNetworking.send(player, new Ping(server.getTickCount()));
            }
          }
          REMEMBERED.addAll(server.getPlayerList().getPlayers());
          for (ServerPlayer player : REMEMBERED) {
            player.connection.send(new ClientboundSetExperiencePacket(0, 0, 0));
          }
        });
  }
}
