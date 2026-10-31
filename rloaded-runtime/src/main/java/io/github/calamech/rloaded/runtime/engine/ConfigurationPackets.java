package io.github.calamech.rloaded.runtime.engine;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps play packets away from players in the configuration phase. A mod that holds on to a player
 * and sends it a play packet while an add reconfigures it would otherwise stall the
 * reconfiguration: the packet cannot be encoded in that phase. Such packets are dropped and
 * counted; the player gets the current state when it is back in the world.
 */
public final class ConfigurationPackets {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");
  private static final Set<PacketType<?>> CLIENTBOUND = clientbound();
  private static final AtomicLong DROPPED = new AtomicLong();
  private static final Set<PacketType<?>> REPORTED = ConcurrentHashMap.newKeySet();

  private ConfigurationPackets() {}

  /**
   * Whether {@code packet}, about to be sent on a connection whose outbound protocol is {@code
   * outbound}, cannot be sent in that phase; counts it when so.
   *
   * @param outbound the connection's outbound protocol, or null before it is first set
   */
  public static boolean drop(ConnectionProtocol outbound, Packet<?> packet) {
    PacketType<?> type = packet.type();
    if (outbound != ConnectionProtocol.CONFIGURATION
        || type.flow() != PacketFlow.CLIENTBOUND
        || CLIENTBOUND.contains(type)) {
      return false;
    }
    DROPPED.incrementAndGet();
    if (REPORTED.add(type)) {
      LOGGER.info(
          "Dropped {} sent to a player in the configuration phase; later ones are counted only",
          type.id());
    }
    return true;
  }

  /** Packets dropped since the game started. */
  public static long dropped() {
    return DROPPED.get();
  }

  private static Set<PacketType<?>> clientbound() {
    Set<PacketType<?>> types = new HashSet<>();
    ConfigurationProtocols.CLIENTBOUND_TEMPLATE
        .details()
        .listPackets((type, id) -> types.add(type));
    return Set.copyOf(types);
  }
}
