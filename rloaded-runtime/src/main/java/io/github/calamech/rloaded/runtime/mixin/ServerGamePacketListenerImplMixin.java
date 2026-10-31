package io.github.calamech.rloaded.runtime.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import io.github.calamech.rloaded.runtime.engine.Reconfigurations;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player sent to the configuration phase is not announced as leaving (see {@link
 * Reconfigurations}).
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerImplMixin {
  @Shadow public ServerPlayer player;

  // Mixin calls these from the injected methods, which the compiler cannot see, with every
  // parameter of the target whether used or not.
  @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
  @Inject(method = "switchToConfig", at = @At("HEAD"))
  private void rloaded$rememberAway(CallbackInfo callback) {
    Reconfigurations.sentToConfiguration(player.getUUID());
  }

  @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
  @Inject(method = "onDisconnect", at = @At("HEAD"))
  private void rloaded$forgetAway(DisconnectionDetails details, CallbackInfo callback) {
    Reconfigurations.disconnectedFromWorld(player.getUUID());
  }

  @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
  @WrapWithCondition(
      method = "removePlayerFromWorld",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
  private boolean rloaded$announceLeaving(PlayerList players, Component message, boolean overlay) {
    return Reconfigurations.announceLeaving(player.getUUID());
  }
}
