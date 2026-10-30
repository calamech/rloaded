package io.github.calamech.rloaded.gametest.mixin;

import io.github.calamech.rloaded.gametest.FieldInitProbe;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Adds a field with an initializer to a block entity class that is already loaded. */
@Mixin(ChestBlockEntity.class)
public abstract class ChestFieldInitMixin implements FieldInitProbe {
  @Unique private String rloadedMarker = "initialized";

  @Override
  public String rloadedMarker() {
    return rloadedMarker;
  }
}
