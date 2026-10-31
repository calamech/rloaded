package io.github.calamech.rloaded.runtime.mixin;

import io.github.calamech.rloaded.runtime.singleplayer.WorldGate;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link WorldGate} get the game ready for a singleplayer world before the world loads, and
 * before a new world is created.
 */
@Mixin(WorldOpenFlows.class)
abstract class WorldOpenFlowsMixin {
  // Mixin calls these from the injected methods, which the compiler cannot see, with every
  // parameter of the target whether used or not.
  @SuppressWarnings("UnusedMethod")
  @Inject(method = "openWorld", at = @At("HEAD"), cancellable = true)
  private void rloaded$prepareWorld(String levelId, Runnable onCancel, CallbackInfo callback) {
    if (!WorldGate.beforeOpen(levelId, onCancel)) {
      callback.cancel();
    }
  }

  @SuppressWarnings("UnusedMethod")
  @Inject(method = "createFreshLevel", at = @At("HEAD"), cancellable = true)
  private void rloaded$restartBeforeCreating(CallbackInfo callback) {
    if (!WorldGate.beforeCreate()) {
      callback.cancel();
    }
  }
}
