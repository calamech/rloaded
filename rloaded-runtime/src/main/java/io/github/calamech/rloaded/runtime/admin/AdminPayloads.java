package io.github.calamech.rloaded.runtime.admin;

import io.github.calamech.rloaded.protocol.AdminEvent;
import io.github.calamech.rloaded.protocol.AdminRequest;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.protocol.ProtocolCodec;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The play-phase channel between a player's Mods screen and the server: {@code AdminRequest} from
 * the client, {@code AdminEvent} from the server, each encoded by {@link ProtocolCodec}.
 */
public final class AdminPayloads {
  private AdminPayloads() {}

  private static StreamCodec<FriendlyByteBuf, byte[]> rawCodec() {
    return ByteBufCodecs.byteArray(ProtocolCodec.MAX_MESSAGE_BYTES).cast();
  }

  /** A request from a player's Mods screen. */
  // Payloads are moved between the network and the protocol code, never compared or hashed.
  @SuppressWarnings("ArrayRecordComponent")
  public record Request(byte[] bytes) implements CustomPacketPayload {
    public static final Type<Request> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("rloaded", "admin_request"));
    public static final StreamCodec<FriendlyByteBuf, Request> CODEC =
        rawCodec().map(Request::new, Request::bytes);

    /** Encodes {@code request}. */
    public static Request of(AdminRequest request) {
      return new Request(
          ProtocolCodec.INSTANCE.encode(AdminRequest.Companion.serializer(), request));
    }

    /** The request, or what is wrong with its bytes. */
    public Outcome<AdminRequest, ?> decode() {
      return ProtocolCodec.INSTANCE.decode(AdminRequest.Companion.serializer(), bytes);
    }

    @Override
    public Type<Request> type() {
      return TYPE;
    }
  }

  /** An event for a player's Mods screen. */
  // Payloads are moved between the network and the protocol code, never compared or hashed.
  @SuppressWarnings("ArrayRecordComponent")
  public record Event(byte[] bytes) implements CustomPacketPayload {
    public static final Type<Event> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("rloaded", "admin_event"));
    public static final StreamCodec<FriendlyByteBuf, Event> CODEC =
        rawCodec().map(Event::new, Event::bytes);

    /** Encodes {@code event}. */
    public static Event of(AdminEvent event) {
      return new Event(ProtocolCodec.INSTANCE.encode(AdminEvent.Companion.serializer(), event));
    }

    /** The event, or what is wrong with its bytes. */
    public Outcome<AdminEvent, ?> decode() {
      return ProtocolCodec.INSTANCE.decode(AdminEvent.Companion.serializer(), bytes);
    }

    @Override
    public Type<Event> type() {
      return TYPE;
    }
  }

  /** Registers the payload types; both sides call it. */
  public static void register() {
    PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
    PayloadTypeRegistry.clientboundPlay().register(Event.TYPE, Event.CODEC);
  }
}
