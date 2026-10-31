package io.github.calamech.rloaded.runtime.engine;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Keeps the chat quiet while an add sends players through the configuration phase. The game
 * announces that each of them left the game and joined it again, though nobody really left: those
 * two messages are skipped for a player sent back to configuration, and announced as usual for real
 * joins and leaves. A player who disconnects before coming back did leave, and is announced then.
 */
public final class Reconfigurations {
  // Players sent to configuration and not back in the world yet. Written on the server thread,
  // removed on a disconnection, which Fabric reports from the network thread.
  private static final Set<UUID> AWAY = ConcurrentHashMap.newKeySet();

  private Reconfigurations() {}

  /** Announces players who disconnect while they are away. The game calls it once. */
  public static void register() {
    ServerConfigurationConnectionEvents.DISCONNECT.register(
        (listener, server) -> {
          if (AWAY.remove(listener.getOwner().id())) {
            Component left =
                Component.translatable(
                        "multiplayer.player.left", Component.literal(listener.getOwner().name()))
                    .withStyle(ChatFormatting.YELLOW);
            server.execute(() -> server.getPlayerList().broadcastSystemMessage(left, false));
          }
        });
  }

  /** {@code player} leaves the world for the configuration phase, and will come back. */
  public static void sentToConfiguration(UUID player) {
    AWAY.add(player);
  }

  /**
   * {@code player} disconnected while still in the world, before its configuration started: it is
   * announced as leaving.
   */
  public static void disconnectedFromWorld(UUID player) {
    AWAY.remove(player);
  }

  /** Whether the game announces that {@code player} left the world. */
  public static boolean announceLeaving(UUID player) {
    return !AWAY.contains(player);
  }

  /** Whether the game announces that {@code player} joined; it is back in the world either way. */
  public static boolean announceJoining(UUID player) {
    return !AWAY.remove(player);
  }
}
