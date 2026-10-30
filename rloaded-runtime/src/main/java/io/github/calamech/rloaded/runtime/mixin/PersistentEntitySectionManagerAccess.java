package io.github.calamech.rloaded.runtime.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reaches the steps an entity manager runs when a chunk unloads and loads again. */
@Mixin(PersistentEntitySectionManager.class)
public interface PersistentEntitySectionManagerAccess {
  /**
   * Returns the load status of each chunk the manager knows.
   *
   * @return the statuses, by packed chunk position
   */
  @Accessor("chunkLoadStatuses")
  Long2ObjectMap<?> rloaded$chunkLoadStatuses();

  /**
   * Saves the entities of a chunk to storage and removes them from the level.
   *
   * @param chunkKey the packed chunk position
   * @return whether the entities were saved and removed
   */
  @Invoker("processChunkUnload")
  boolean rloaded$processChunkUnload(long chunkKey);

  /**
   * Starts reading the entities of a chunk from storage; they join the level on a later tick.
   *
   * @param chunkKey the packed chunk position
   */
  @Invoker("requestChunkLoad")
  void rloaded$requestChunkLoad(long chunkKey);
}
