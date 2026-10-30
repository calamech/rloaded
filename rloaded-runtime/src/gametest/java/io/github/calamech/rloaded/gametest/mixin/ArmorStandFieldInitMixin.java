package io.github.calamech.rloaded.gametest.mixin;

import io.github.calamech.rloaded.gametest.FieldInitProbe;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Adds a field with an initializer to an entity class that is already loaded. */
@Mixin(ArmorStand.class)
public abstract class ArmorStandFieldInitMixin implements FieldInitProbe {
  @Unique private String rloadedMarker = "initialized";

  @Override
  public String rloadedMarker() {
    return rloadedMarker;
  }
}
