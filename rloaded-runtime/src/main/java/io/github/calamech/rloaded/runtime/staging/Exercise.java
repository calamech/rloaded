package io.github.calamech.rloaded.runtime.staging;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Puts new content into a level: every block placed, every summonable entity type spawned, every
 * item dropped as an item entity, each in a row of its own on the surface, in chunks kept loaded so
 * what was put there keeps ticking. A failure is described and the next piece of content is tried.
 */
final class Exercise {
  private static final int ROW = 32;
  private static final int SPACING = 2;
  private static final int BLOCKS_Z = 0;
  private static final int ENTITIES_Z = 128;
  private static final int ITEMS_Z = 256;

  /** What was put into the level, and what failed. */
  record Result(int blocks, int entities, int items, List<String> failures) {
    static final Result NONE = new Result(0, 0, 0, List.of());
  }

  private Exercise() {}

  /** Places, spawns and drops {@code content} in {@code level}. */
  static Result run(ServerLevel level, NewContent content) {
    List<String> failures = new ArrayList<>();
    int blocks = 0;
    for (int i = 0; i < content.blocks().size(); i++) {
      Block block = content.blocks().get(i);
      try {
        level.setBlockAndUpdate(surface(level, i, BLOCKS_Z), block.defaultBlockState());
        blocks++;
      } catch (RuntimeException e) {
        failures.add("placing " + BuiltInRegistries.BLOCK.getKey(block) + " threw " + e);
      }
    }
    int entities = 0;
    for (int i = 0; i < content.entities().size(); i++) {
      EntityType<?> type = content.entities().get(i);
      try {
        if (type.canSummon()
            && type.spawn(level, surface(level, i, ENTITIES_Z), EntitySpawnReason.COMMAND)
                != null) {
          entities++;
        }
      } catch (RuntimeException e) {
        failures.add("spawning " + BuiltInRegistries.ENTITY_TYPE.getKey(type) + " threw " + e);
      }
    }
    int items = 0;
    for (int i = 0; i < content.items().size(); i++) {
      Item item = content.items().get(i);
      try {
        BlockPos at = surface(level, i, ITEMS_Z);
        level.addFreshEntity(
            new ItemEntity(
                level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, new ItemStack(item)));
        items++;
      } catch (RuntimeException e) {
        failures.add("dropping " + BuiltInRegistries.ITEM.getKey(item) + " threw " + e);
      }
    }
    return new Result(blocks, entities, items, List.copyOf(failures));
  }

  // The first free block above the ground at the index's place in its row, its chunk kept loaded.
  private static BlockPos surface(ServerLevel level, int index, int rowStart) {
    int x = (index % ROW) * SPACING;
    int z = rowStart + (index / ROW) * SPACING;
    level.setChunkForced(x >> 4, z >> 4, true);
    return new BlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), z);
  }
}
