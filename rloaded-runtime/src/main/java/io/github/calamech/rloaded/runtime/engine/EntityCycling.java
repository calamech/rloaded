package io.github.calamech.rloaded.runtime.engine;

import io.github.calamech.rloaded.runtime.mixin.PersistentEntitySectionManagerAccess;
import io.github.calamech.rloaded.runtime.mixin.ServerLevelAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

/**
 * Rebuilds the entities of a server level from storage, so that they are new objects of the current
 * classes.
 *
 * <p>A late Mixin adds fields to entity classes, but objects that already exist never run the new
 * field initializers. This class replays, for every chunk whose entities are loaded, what the level
 * does when that chunk unloads and loads again: the entities are saved to storage and removed, then
 * read back as new objects. The chunks themselves stay loaded. Players are not saved with chunks;
 * reconfiguration rebuilds them.
 */
public final class EntityCycling {
  private EntityCycling() {}

  /**
   * Saves and removes the entities of every chunk of {@code level} whose entities are loaded, then
   * starts reading them back. Must run on the server thread. The entities rejoin the level on a
   * later tick: {@link #isComplete} tells when.
   *
   * @return the packed positions of the cycled chunks
   */
  public static long[] cycle(ServerLevel level) {
    if (!level.getServer().isSameThread()) {
      throw new IllegalStateException("entities can only be cycled on the server thread");
    }
    PersistentEntitySectionManager<Entity> manager =
        ((ServerLevelAccess) level).rloaded$entityManager();
    PersistentEntitySectionManagerAccess access = (PersistentEntitySectionManagerAccess) manager;
    long[] loaded =
        access
            .rloaded$chunkLoadStatuses()
            .keySet()
            .longStream()
            .filter(manager::areEntitiesLoaded)
            .toArray();
    for (long chunk : loaded) {
      if (!access.rloaded$processChunkUnload(chunk)) {
        throw new IllegalStateException("the entities of chunk " + chunk + " could not be saved");
      }
      access.rloaded$requestChunkLoad(chunk);
    }
    return loaded;
  }

  /**
   * Tells whether the entities of the given chunks are back in {@code level}.
   *
   * @param chunks packed chunk positions, as returned by {@link #cycle}
   * @return whether every chunk has its entities loaded again
   */
  public static boolean isComplete(ServerLevel level, long[] chunks) {
    PersistentEntitySectionManager<Entity> manager =
        ((ServerLevelAccess) level).rloaded$entityManager();
    for (long chunk : chunks) {
      if (!manager.areEntitiesLoaded(chunk)) {
        return false;
      }
    }
    return true;
  }
}
