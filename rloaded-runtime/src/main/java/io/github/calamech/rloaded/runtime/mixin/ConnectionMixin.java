package io.github.calamech.rloaded.runtime.mixin;

import io.github.calamech.rloaded.runtime.engine.ConfigurationPackets;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Remembers the phase each connection sends in, and drops what cannot be sent in it (see {@link
 * ConfigurationPackets}).
 */
@Mixin(Connection.class)
abstract class ConnectionMixin {
  // Set on the server thread, read by any thread that sends.
  @Unique private volatile ConnectionProtocol rloaded$outbound;

  // Mixin calls these from the injected methods, which the compiler cannot see, with every
  // parameter of the target whether used or not.
  @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
  @Inject(method = "setupOutboundProtocol", at = @At("HEAD"))
  private void rloaded$rememberOutbound(ProtocolInfo<?> protocol, CallbackInfo callback) {
    rloaded$outbound = protocol.id();
  }

  @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
  @Inject(
      method =
          "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
      at = @At("HEAD"),
      cancellable = true)
  private void rloaded$dropWhatThePhaseCannotSend(
      Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo callback) {
    if (ConfigurationPackets.drop(rloaded$outbound, packet)) {
      callback.cancel();
    }
  }
}
