package io.github.calamech.rloaded.runtime.mixin;

import io.github.calamech.rloaded.runtime.staging.StagingDryRun;
import java.io.IOException;
import java.net.InetAddress;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps a staging server off the network: nobody joins it, and the real server it runs next to
 * often holds the game port.
 */
@Mixin(DedicatedServer.class)
abstract class DedicatedServerMixin {
  // Mixin calls it from the redirected call site, which the compiler cannot see.
  @SuppressWarnings("UnusedMethod")
  @Redirect(
      method = "initServer",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/server/network/ServerConnectionListener;startTcpServerListener(Ljava/net/InetAddress;I)V"))
  private void rloaded$listenUnlessStaging(
      ServerConnectionListener listener, InetAddress address, int port) throws IOException {
    if (!StagingDryRun.active()) {
      listener.startTcpServerListener(address, port);
    }
  }
}
