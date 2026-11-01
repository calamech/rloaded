package io.github.calamech.rloaded.testmods.jointest.mixin;

import net.minecraft.network.Connection;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * For a player expected to be disconnected, holds the network thread for a moment between closing
 * the connection and recording why, as a busy machine sometimes does. The client thread then sees
 * the closed connection first and reports the game's generic reason at every run, so the join check
 * shows it does not depend on which thread wins.
 */
@Mixin(Connection.class)
abstract class LateDisconnectReason {
  private static final long HOLD_MILLIS = 500;

  // Mixin calls this from the injected method, which the compiler cannot see, with every
  // parameter of the target whether used or not.
  @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
  @Inject(
      method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V",
      at =
          @At(
              value = "FIELD",
              target =
                  "Lnet/minecraft/network/Connection;disconnectionDetails:Lnet/minecraft/network/DisconnectionDetails;",
              opcode = Opcodes.PUTFIELD))
  private void rloaded$hold(CallbackInfo callback) {
    if (!Boolean.getBoolean("rloaded.join-test.expect-disconnect")) {
      return;
    }
    try {
      Thread.sleep(HOLD_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
