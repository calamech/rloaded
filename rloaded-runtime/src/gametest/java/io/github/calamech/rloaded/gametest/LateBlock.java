package io.github.calamech.rloaded.gametest;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** A block with four states, registered while the game runs. */
final class LateBlock extends Block {
  LateBlock(Properties properties) {
    super(properties);
  }

  @Override
  protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(BlockStateProperties.LIT, BlockStateProperties.POWERED);
  }
}
