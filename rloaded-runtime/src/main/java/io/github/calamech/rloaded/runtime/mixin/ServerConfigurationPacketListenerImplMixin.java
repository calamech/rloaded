package io.github.calamech.rloaded.runtime.mixin;

import io.github.calamech.rloaded.runtime.engine.Reconfigurations;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player back in the configuration phase for an add is not asked again what it answered when it
 * joined: the code of conduct and the server resource pack (see {@link Reconfigurations}).
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
abstract class ServerConfigurationPacketListenerImplMixin {
  // Mixin calls this from the injected method, which the compiler cannot see, with every
  // parameter of the target whether used or not.
  @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
  @Inject(method = "addOptionalTasks", at = @At("HEAD"), cancellable = true)
  private void rloaded$skipAnsweredTasks(CallbackInfo callback) {
    ServerConfigurationPacketListenerImpl self =
        (ServerConfigurationPacketListenerImpl) (Object) this;
    if (Reconfigurations.isAway(self.getOwner().id())) {
      callback.cancel();
    }
  }
}
