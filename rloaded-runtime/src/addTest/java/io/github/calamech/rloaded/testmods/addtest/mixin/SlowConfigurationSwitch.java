package io.github.calamech.rloaded.testmods.addtest.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the client thread for a moment between acknowledging the switch to the configuration phase
 * and switching its outbound protocol, as a busy machine sometimes does. The server's answer then
 * arrives inside that window at every add, so the add checks show what happens to packets sent in
 * it, instead of once in a few dozen adds.
 */
@Mixin(ClientPacketListener.class)
abstract class SlowConfigurationSwitch {
  private static final long HOLD_MILLIS = 200;

  // Mixin calls this from the injected method, which the compiler cannot see, with every
  // parameter of the target whether used or not.
  @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
  @Inject(
      method = "handleConfigurationStart",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/network/Connection;setupOutboundProtocol(Lnet/minecraft/network/ProtocolInfo;)V"))
  private void rloaded$hold(CallbackInfo callback) {
    try {
      Thread.sleep(HOLD_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
