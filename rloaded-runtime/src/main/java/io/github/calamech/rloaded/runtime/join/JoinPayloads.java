package io.github.calamech.rloaded.runtime.join;

import io.github.calamech.rloaded.protocol.ProtocolCodec;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The four configuration-phase channels of a join. Each payload carries one message of {@code
 * rloaded-protocol}, encoded by {@link ProtocolCodec}; the game only moves the bytes.
 */
public final class JoinPayloads {
  private JoinPayloads() {}

  private static StreamCodec<FriendlyByteBuf, byte[]> rawCodec() {
    return ByteBufCodecs.byteArray(ProtocolCodec.MAX_MESSAGE_BYTES).cast();
  }

  private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String path) {
    return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("rloaded", path));
  }

  /** {@code ServerHello} from the server, {@code ClientHello} from the client. */
  // Payloads are moved between the network and the protocol code, never compared or hashed.
  @SuppressWarnings("ArrayRecordComponent")
  public record Hello(byte[] bytes) implements CustomPacketPayload {
    public static final Type<Hello> TYPE = typeOf("hello");
    public static final StreamCodec<FriendlyByteBuf, Hello> CODEC =
        rawCodec().map(Hello::new, Hello::bytes);

    @Override
    public Type<Hello> type() {
      return TYPE;
    }
  }

  /** The signed manifest, from the server. */
  // Payloads are moved between the network and the protocol code, never compared or hashed.
  @SuppressWarnings("ArrayRecordComponent")
  public record Manifest(byte[] bytes) implements CustomPacketPayload {
    public static final Type<Manifest> TYPE = typeOf("manifest");
    public static final StreamCodec<FriendlyByteBuf, Manifest> CODEC =
        rawCodec().map(Manifest::new, Manifest::bytes);

    @Override
    public Type<Manifest> type() {
      return TYPE;
    }
  }

  /** Where the client's install is, from the client. */
  // Payloads are moved between the network and the protocol code, never compared or hashed.
  @SuppressWarnings("ArrayRecordComponent")
  public record Progress(byte[] bytes) implements CustomPacketPayload {
    public static final Type<Progress> TYPE = typeOf("progress");
    public static final StreamCodec<FriendlyByteBuf, Progress> CODEC =
        rawCodec().map(Progress::new, Progress::bytes);

    @Override
    public Type<Progress> type() {
      return TYPE;
    }
  }

  /** How the client's install ended, from the client. */
  // Payloads are moved between the network and the protocol code, never compared or hashed.
  @SuppressWarnings("ArrayRecordComponent")
  public record Result(byte[] bytes) implements CustomPacketPayload {
    public static final Type<Result> TYPE = typeOf("result");
    public static final StreamCodec<FriendlyByteBuf, Result> CODEC =
        rawCodec().map(Result::new, Result::bytes);

    @Override
    public Type<Result> type() {
      return TYPE;
    }
  }

  /** Registers the payload types; both sides call it. */
  public static void register() {
    PayloadTypeRegistry.clientboundConfiguration().register(Hello.TYPE, Hello.CODEC);
    PayloadTypeRegistry.clientboundConfiguration().register(Manifest.TYPE, Manifest.CODEC);
    PayloadTypeRegistry.serverboundConfiguration().register(Hello.TYPE, Hello.CODEC);
    PayloadTypeRegistry.serverboundConfiguration().register(Progress.TYPE, Progress.CODEC);
    PayloadTypeRegistry.serverboundConfiguration().register(Result.TYPE, Result.CODEC);
  }
}
