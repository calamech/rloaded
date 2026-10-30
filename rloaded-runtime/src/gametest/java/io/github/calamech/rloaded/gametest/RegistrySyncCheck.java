package io.github.calamech.rloaded.gametest;

import io.github.calamech.rloaded.runtime.engine.RegistryThaw;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * What the server and the clients of the registry sync check share: the blocks each of them
 * registers while the game runs, in an order of its own, and the raw ids each writes for the
 * harness to compare.
 */
public final class RegistrySyncCheck {
  private static final String OUTPUT_PROPERTY = "rloaded.registry-sync.output";
  private static final String ORDER_PROPERTY = "rloaded.registry-sync.order";

  private RegistrySyncCheck() {}

  /** Sent by a client once it registered the blocks, so the server knows it can add them too. */
  public record BlocksRegistered() implements CustomPacketPayload {
    /** The payload type, from client to server. */
    public static final Type<BlocksRegistered> TYPE =
        new Type<>(Identifier.fromNamespaceAndPath("rloaded-gametest", "blocks_registered"));

    /** The packet has no content. */
    public static final StreamCodec<RegistryFriendlyByteBuf, BlocksRegistered> CODEC =
        StreamCodec.unit(new BlocksRegistered());

    @Override
    public Type<BlocksRegistered> type() {
      return TYPE;
    }
  }

  static void registerPayloads() {
    PayloadTypeRegistry.serverboundPlay().register(BlocksRegistered.TYPE, BlocksRegistered.CODEC);
  }

  /** The directory this process writes its raw ids to, set only when the harness runs the check. */
  public static Optional<Path> output() {
    return Optional.ofNullable(System.getProperty(OUTPUT_PROPERTY)).map(Path::of);
  }

  /** Registers the check's blocks in the order the harness gave this process. Game thread only. */
  public static void registerBlocks() {
    String order = System.getProperty(ORDER_PROPERTY);
    if (order == null) {
      throw new IllegalStateException("run with the " + ORDER_PROPERTY + " property");
    }
    List<String> paths = Arrays.asList(order.split(","));
    RegistryThaw.thaw(BuiltInRegistries.BLOCK);
    for (String path : paths) {
      ResourceKey<Block> key =
          ResourceKey.create(
              Registries.BLOCK, Identifier.fromNamespaceAndPath("rloaded-gametest", path));
      Registry.register(
          BuiltInRegistries.BLOCK, key, new LateBlock(BlockBehaviour.Properties.of().setId(key)));
    }
    RegistryThaw.freeze(BuiltInRegistries.BLOCK);
  }

  /**
   * Writes the raw id of every block and of every block state, one per line, as {@code block
   * namespace:path raw-id} and {@code state description raw-id}, sorted.
   */
  public static void writeIds(Path file) {
    Stream<String> blocks =
        BuiltInRegistries.BLOCK.stream()
            .map(
                block ->
                    "block "
                        + BuiltInRegistries.BLOCK.getKey(block)
                        + " "
                        + BuiltInRegistries.BLOCK.getId(block));
    Stream<String> states =
        StreamSupport.stream(Block.BLOCK_STATE_REGISTRY.spliterator(), false)
            .map(state -> "state " + state + " " + Block.BLOCK_STATE_REGISTRY.getId(state));
    List<String> lines = Stream.concat(blocks, states).sorted().toList();
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, lines, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot write the raw ids to " + file, e);
    }
  }
}
