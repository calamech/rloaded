package io.github.calamech.rloaded.runtime.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reaches the chunks a server level holds. */
@Mixin(ChunkMap.class)
public interface ChunkMapAccess {
  /**
   * Returns the chunks the level holds, loaded or loading.
   *
   * @return the chunk holders, by packed chunk position
   */
  @Accessor("visibleChunkMap")
  Long2ObjectLinkedOpenHashMap<ChunkHolder> rloaded$visibleChunkMap();
}
