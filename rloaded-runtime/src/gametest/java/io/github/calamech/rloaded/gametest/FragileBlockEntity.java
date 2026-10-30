package io.github.calamech.rloaded.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;

/** A block entity that fails whenever it is read from saved data. */
final class FragileBlockEntity extends BlockEntity {
  FragileBlockEntity(BlockPos worldPosition, BlockState blockState) {
    super(GameTestContent.FRAGILE_BLOCK_ENTITY, worldPosition, blockState);
  }

  @Override
  protected void loadAdditional(ValueInput input) {
    throw new IllegalStateException("fragile block entities cannot be read back");
  }
}
