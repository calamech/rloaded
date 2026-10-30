package io.github.calamech.rloaded.gametest;

import io.github.calamech.rloaded.runtime.engine.RegistryThaw;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Checks that a block can be registered after startup, and placed. */
public final class RegistryThawGameTest {
  private static final BlockPos HERE = new BlockPos(1, 1, 1);

  @GameTest
  public void blockRegisteredAfterStartupGetsTheNextIds(GameTestHelper helper) {
    Registry<Block> blocks = BuiltInRegistries.BLOCK;
    int blockCount = blocks.size();
    int stateCount = Block.BLOCK_STATE_REGISTRY.size();
    int stoneId = blocks.getId(Blocks.STONE);
    ResourceKey<Block> key = key("late_block");

    RegistryThaw.thaw(blocks);
    Block late =
        Registry.register(blocks, key, new LateBlock(BlockBehaviour.Properties.of().setId(key)));
    RegistryThaw.freeze(blocks);

    helper.assertValueEqual(blocks.getId(late), blockCount, "raw id of the new block");
    helper.assertValueEqual(blocks.getId(Blocks.STONE), stoneId, "raw id of stone");
    List<Integer> stateIds =
        StreamSupport.stream(Block.BLOCK_STATE_REGISTRY.spliterator(), false)
            .filter(state -> state.is(late))
            .map(Block.BLOCK_STATE_REGISTRY::getId)
            .sorted()
            .toList();
    helper.assertValueEqual(
        stateIds,
        IntStream.range(stateCount, stateCount + 4).boxed().toList(),
        "state ids of the new block");
    Holder.Reference<Block> holder = blocks.getOrThrow(key);
    helper.assertValueEqual(holder.value(), late, "block of the registered holder");
    helper.assertValueEqual(holder.tags().count(), 0L, "tags of the new block");
    helper.assertTrue(
        refusesNewBlocks(), "the block registry accepts new blocks after freezing again");

    BlockState lit = late.defaultBlockState().setValue(BlockStateProperties.LIT, true);
    helper.setBlock(HERE, lit);
    helper.assertBlockState(HERE, lit);
    helper.succeed();
  }

  @GameTest
  public void freezeRefusesABlockThatWasNeverRegistered(GameTestHelper helper) {
    Registry<Block> blocks = BuiltInRegistries.BLOCK;
    ResourceKey<Block> key = key("orphan_block");

    RegistryThaw.thaw(blocks);
    Block orphan = new LateBlock(BlockBehaviour.Properties.of().setId(key));
    String refusal = null;
    try {
      RegistryThaw.freeze(blocks);
    } catch (IllegalStateException e) {
      refusal = e.getMessage();
    }

    helper.assertTrue(
        refusal != null
            && refusal.startsWith(
                "values created holders in registry minecraft:block but were never registered"),
        "unexpected refusal: " + refusal);
    Registry.register(blocks, key, orphan);
    RegistryThaw.freeze(blocks);
    helper.assertTrue(
        refusesNewBlocks(), "the block registry accepts new blocks after freezing again");
    helper.succeed();
  }

  private static boolean refusesNewBlocks() {
    try {
      new LateBlock(BlockBehaviour.Properties.of().setId(key("refused_block")));
      return false;
    } catch (IllegalStateException e) {
      return true;
    }
  }

  private static ResourceKey<Block> key(String path) {
    return ResourceKey.create(
        Registries.BLOCK, Identifier.fromNamespaceAndPath("rloaded-gametest", path));
  }
}
