package io.github.calamech.rloaded.testmods.latelifecycle.mixin;

import io.github.calamech.rloaded.testmods.latelifecycle.LateLifecycle;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reads the server state at each player tick: without it, the player is disconnected. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
  @SuppressWarnings({"UnusedMethod", "UnusedVariable"}) // Called by Mixin, with its callback info.
  @Inject(method = "tick", at = @At("HEAD"))
  private void lateLifecycle$useServerState(CallbackInfo info) {
    if (LateLifecycle.serverState() == null) {
      throw new IllegalStateException("late-lifecycle was never told the server started");
    }
  }
}
