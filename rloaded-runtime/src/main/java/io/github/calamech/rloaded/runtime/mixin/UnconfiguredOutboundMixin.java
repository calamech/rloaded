package io.github.calamech.rloaded.runtime.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds the packets written while a connection switches its outbound protocol, and writes them, in
 * order, once the new protocol is set. A side that sends the packet ending a phase has no outbound
 * protocol until its own thread sets the next one. Fabric API answers the server's channel
 * registration from the network thread, which an add's reconfiguration can make arrive in that
 * window; the game refused the packet and dropped the player. A packet sent after the one that ends
 * a phase belongs to the next phase, so it is encoded with that phase's protocol.
 */
@Mixin(UnconfiguredPipelineHandler.Outbound.class)
abstract class UnconfiguredOutboundMixin {
  // A window lasts a few packets; past this, something is wrong and the game refuses as before.
  @Unique private static final int MAX_HELD = 64;

  // Only the connection's network thread writes.
  @Unique private final List<Held> rloaded$held = new ArrayList<>();

  @Unique
  private record Held(Object packet, ChannelPromise promise) {}

  // Mixin calls this from the injected method, which the compiler cannot see, with every
  // parameter of the target whether used or not.
  @SuppressWarnings({"UnusedMethod", "UnusedVariable"})
  @Inject(method = "write", at = @At("HEAD"), cancellable = true)
  private void rloaded$holdUntilConfigured(
      ChannelHandlerContext context,
      Object message,
      ChannelPromise promise,
      CallbackInfo callback) {
    if (message instanceof Packet<?> && rloaded$held.size() < MAX_HELD) {
      rloaded$held.add(new Held(message, promise));
      callback.cancel();
    } else if (message instanceof UnconfiguredPipelineHandler.OutboundConfigurationTask task
        && !rloaded$held.isEmpty()) {
      try {
        task.run(context);
      } finally {
        ReferenceCountUtil.release(message);
      }
      promise.setSuccess();
      // This handler is out of the pipeline now: the packets go through the new encoder. Each
      // sender's promise reports its packet's outcome.
      Channel channel = context.channel();
      for (Held held : rloaded$held) {
        ChannelFuture _ = channel.write(held.packet(), held.promise());
      }
      rloaded$held.clear();
      channel.flush();
      callback.cancel();
    }
  }
}
