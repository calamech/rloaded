package io.github.calamech.rloaded.gametest;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** A block with 32768 states, enough to push the block state count past the next power of two. */
final class WideBlock extends Block {
  private static final IntegerProperty FIRST = IntegerProperty.create("first", 0, 63);
  private static final IntegerProperty SECOND = IntegerProperty.create("second", 0, 63);
  private static final IntegerProperty THIRD = IntegerProperty.create("third", 0, 7);

  WideBlock(Properties properties) {
    super(properties);
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(FIRST, SECOND, THIRD);
  }
}
