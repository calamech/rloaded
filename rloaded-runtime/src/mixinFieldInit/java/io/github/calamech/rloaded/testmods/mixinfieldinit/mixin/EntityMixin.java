package io.github.calamech.rloaded.testmods.mixinfieldinit.mixin;

import io.github.calamech.rloaded.testmods.mixinfieldinit.FieldInitMarker;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Adds a field with an initializer to every entity, a class loaded long before the mod. */
@Mixin(Entity.class)
public abstract class EntityMixin implements FieldInitMarker {
  @Unique private String mixinFieldInit$marker = "initialized";

  @Override
  public String mixinFieldInit$marker() {
    return mixinFieldInit$marker;
  }
}
