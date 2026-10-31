package io.github.calamech.rloaded.runtime.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.calamech.rloaded.runtime.engine.Reconfigurations;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A player back from the configuration phase is not announced as joining (see {@link
 * Reconfigurations}).
 */
@Mixin(PlayerList.class)
abstract class PlayerListMixin {
  // Mixin calls this from the injected method, which the compiler cannot see, with every
  // parameter of the target whether used or not.
  @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
  @WrapWithCondition(
      method = "placeNewPlayer",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"))
  private boolean rloaded$announceJoining(
      PlayerList players,
      Component message,
      boolean overlay,
      @Local(argsOnly = true) ServerPlayer player) {
    return Reconfigurations.announceJoining(player.getUUID());
  }
}
