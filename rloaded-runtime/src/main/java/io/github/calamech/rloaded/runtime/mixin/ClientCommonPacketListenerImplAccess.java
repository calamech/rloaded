package io.github.calamech.rloaded.runtime.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reaches the server entry a connection was opened for. */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface ClientCommonPacketListenerImplAccess {
  /**
   * Returns the server entry the player joined.
   *
   * @return the entry, with the address the player typed, or null without one
   */
  @Accessor("serverData")
  ServerData rloaded$serverData();
}
