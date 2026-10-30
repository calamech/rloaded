package io.github.calamech.rloaded.runtime.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reaches the entity manager of a server level. */
@Mixin(ServerLevel.class)
public interface ServerLevelAccess {
  /**
   * Returns the level's entity manager.
   *
   * @return the manager that loads and saves the level's entities by chunk
   */
  @Accessor("entityManager")
  PersistentEntitySectionManager<Entity> rloaded$entityManager();
}
