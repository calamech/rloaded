package io.github.calamech.rloaded.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A block with a {@link FragileBlockEntity}. */
final class FragileBlock extends BaseEntityBlock {
  FragileBlock(Properties properties) {
    super(properties);
  }

  @Override
  public BlockEntity newBlockEntity(BlockPos worldPosition, BlockState blockState) {
    return new FragileBlockEntity(worldPosition, blockState);
  }
}
