package io.github.calamech.rloaded.runtime.engine;

import io.github.calamech.rloaded.runtime.mixin.ChunkMapAccess;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Rebuilds the block entities of a server level from their saved data, so that they are new objects
 * of the current classes.
 *
 * <p>A late Mixin adds fields to block entity classes, but objects that already exist never run the
 * new field initializers. For every loaded chunk, each block entity is saved, read back as a new
 * object, and put in place of the old one, which is marked removed as when its chunk unloads.
 */
public final class BlockEntityCycling {
  private BlockEntityCycling() {}

  /**
   * What a cycle did.
   *
   * @param rebuilt how many block entities were replaced by new objects
   * @param notRebuilt where a block entity could not be read back; the old object stays in place
   *     and the game logged why
   */
  public record Result(int rebuilt, List<BlockPos> notRebuilt) {}

  /**
   * Rebuilds every block entity of the loaded chunks of {@code level}. Must run on the server
   * thread.
   *
   * @return how many block entities were rebuilt, and where one could not be
   */
  public static Result cycle(ServerLevel level) {
    if (!level.getServer().isSameThread()) {
      throw new IllegalStateException("block entities can only be cycled on the server thread");
    }
    long[] held =
        ((ChunkMapAccess) level.getChunkSource().chunkMap)
            .rloaded$visibleChunkMap()
            .keySet()
            .toLongArray();
    int rebuilt = 0;
    List<BlockPos> notRebuilt = new ArrayList<>();
    for (long key : held) {
      ChunkPos position = ChunkPos.unpack(key);
      LevelChunk chunk = level.getChunkSource().getChunkNow(position.x(), position.z());
      if (chunk == null) {
        continue;
      }
      for (BlockEntity old : List.copyOf(chunk.getBlockEntities().values())) {
        BlockPos pos = old.getBlockPos();
        CompoundTag saved = old.saveWithFullMetadata(level.registryAccess());
        BlockEntity fresh =
            BlockEntity.loadStatic(pos, old.getBlockState(), saved, level.registryAccess());
        if (fresh == null) {
          notRebuilt.add(pos);
        } else {
          chunk.removeBlockEntity(pos);
          chunk.addAndRegisterBlockEntity(fresh);
          rebuilt++;
        }
      }
    }
    return new Result(rebuilt, List.copyOf(notRebuilt));
  }
}
