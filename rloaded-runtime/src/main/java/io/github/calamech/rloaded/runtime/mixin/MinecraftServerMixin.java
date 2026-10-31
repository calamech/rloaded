package io.github.calamech.rloaded.runtime.mixin;

import io.github.calamech.rloaded.runtime.admin.LiveAdd;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves an add's rollout on from the connections' tick, which runs every tick and also while the
 * server pauses: a singleplayer server pauses as soon as its player list is empty, which it is
 * while its only player is in the configuration phase, and an empty dedicated server pauses after a
 * minute.
 */
@Mixin(MinecraftServer.class)
abstract class MinecraftServerMixin {
  // Mixin calls this from the injected method, which the compiler cannot see, with the callback
  // whether used or not.
  @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
  @Inject(method = "tickConnection", at = @At("TAIL"))
  private void rloaded$moveRolloutOn(CallbackInfo callback) {
    LiveAdd.connectionsTicked((MinecraftServer) (Object) this);
  }
}
