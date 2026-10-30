package io.github.calamech.rloaded.gametest;

import io.github.calamech.rloaded.runtime.engine.RegistryThaw;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.LongStream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;

/**
 * Checks what happens to chunk sections when blocks added after startup push the number of block
 * states past a power of two.
 *
 * <p>A section with more than 256 different states stores global state ids, with as many bits as
 * the state count needed when the level was created. The test builds such sections with the level's
 * own factory, as the server does, and with a new factory, as a client does when it rebuilds its
 * level after an add.
 */
public final class PaletteBoundaryGameTest {
  private static final int SECTION_SIZE = 4096;
  private static final int DISTINCT_STATES = 300;

  private enum Resend {
    INTACT,
    CHANGED,
    UNREADABLE
  }

  @GameTest
  public void addingStatesPastAPowerOfTwoBreaksGlobalPaletteSections(GameTestHelper helper) {
    PalettedContainerFactory levelFactory = helper.getLevel().palettedContainerFactory();
    int bitsAtStart = Mth.ceillog2(Block.BLOCK_STATE_REGISTRY.size());

    BlockState small = register("palette_small", LateBlock::new).defaultBlockState();
    helper.assertValueEqual(
        Mth.ceillog2(Block.BLOCK_STATE_REGISTRY.size()),
        bitsAtStart,
        "bits after adding four states");
    helper.assertTrue(
        canStore(levelFactory, small), "a section cannot store a new state below the boundary");
    helper.assertTrue(
        reloads(levelFactory, small), "a saved section loses a new state below the boundary");
    helper.assertValueEqual(
        resend(helper, levelFactory), Resend.INTACT, "a section resent below the boundary");

    int statesBefore = Block.BLOCK_STATE_REGISTRY.size();
    BlockState wide = register("palette_wide", WideBlock::new).defaultBlockState();
    helper.assertTrue(
        Mth.ceillog2(Block.BLOCK_STATE_REGISTRY.size()) > bitsAtStart,
        "the wide block did not cross a power of two: "
            + statesBefore
            + " states before, "
            + Block.BLOCK_STATE_REGISTRY.size()
            + " after");
    BlockState pastBoundary =
        Block.BLOCK_STATE_REGISTRY.byId(Block.BLOCK_STATE_REGISTRY.size() - 1);
    helper.assertTrue(
        pastBoundary.is(wide.getBlock()), "the last state id is not the wide block's");

    helper.assertTrue(
        !canStore(levelFactory, pastBoundary),
        "a section of the level stored a state past the boundary");
    helper.assertTrue(
        !reloads(levelFactory, pastBoundary), "a saved section kept a state past the boundary");
    helper.assertValueEqual(
        resend(helper, levelFactory), Resend.UNREADABLE, "a section resent past the boundary");
    helper.succeed();
  }

  // A section of the level with more than 256 states, so it uses global ids.
  private static PalettedContainer<BlockState> globalSection(PalettedContainerFactory factory) {
    PalettedContainer<BlockState> section = factory.createForBlockStates();
    for (int index = 0; index < DISTINCT_STATES; index++) {
      section.set(
          index & 15, index >> 8, (index >> 4) & 15, Block.BLOCK_STATE_REGISTRY.byId(index + 1));
    }
    return section;
  }

  private static boolean canStore(PalettedContainerFactory factory, BlockState state) {
    PalettedContainer<BlockState> section = globalSection(factory);
    try {
      section.set(15, 15, 15, state);
      return state.equals(section.get(15, 15, 15));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  // Reads back, as a chunk load does, saved data whose palette holds the state and 300 others.
  private static boolean reloads(PalettedContainerFactory factory, BlockState state) {
    List<BlockState> palette = new ArrayList<>();
    palette.add(state);
    for (int index = 0; index < DISTINCT_STATES; index++) {
      palette.add(Block.BLOCK_STATE_REGISTRY.byId(index + 1));
    }
    int[] entries = new int[SECTION_SIZE];
    for (int index = 0; index < DISTINCT_STATES + 1; index++) {
      entries[index] = index;
    }
    SimpleBitStorage storage =
        new SimpleBitStorage(Mth.ceillog2(palette.size()), SECTION_SIZE, entries);
    Optional<LongStream> values = Optional.of(LongStream.of(storage.getRaw()));
    PalettedContainer<BlockState> loaded =
        PalettedContainer.unpack(
                factory.blockStatesStrategy(),
                new PalettedContainerRO.PackedData<>(palette, values))
            .getOrThrow();
    return state.equals(loaded.get(0, 0, 0));
  }

  // Sends a level section the way the server does and reads it the way a client does after an add.
  private static Resend resend(GameTestHelper helper, PalettedContainerFactory levelFactory) {
    PalettedContainer<BlockState> sent = globalSection(levelFactory);
    FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
    sent.write(buffer);
    PalettedContainer<BlockState> received =
        PalettedContainerFactory.create(helper.getLevel().registryAccess()).createForBlockStates();
    try {
      received.read(buffer);
    } catch (IndexOutOfBoundsException e) {
      return Resend.UNREADABLE;
    }
    int mismatches = 0;
    for (int y = 0; y < 16; y++) {
      for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
          if (!sent.get(x, y, z).equals(received.get(x, y, z))) {
            mismatches++;
          }
        }
      }
    }
    return mismatches == 0 && buffer.readableBytes() == 0 ? Resend.INTACT : Resend.CHANGED;
  }

  private static Block register(
      String path, Function<BlockBehaviour.Properties, Block> constructor) {
    Registry<Block> blocks = BuiltInRegistries.BLOCK;
    RegistryThaw.thaw(blocks);
    Block block =
        Registry.register(
            blocks, key(path), constructor.apply(BlockBehaviour.Properties.of().setId(key(path))));
    RegistryThaw.freeze(blocks);
    return block;
  }

  private static ResourceKey<Block> key(String path) {
    return ResourceKey.create(
        Registries.BLOCK, Identifier.fromNamespaceAndPath("rloaded-gametest", path));
  }
}
