package io.github.calamech.rloaded.testmods.mixinloadedclass.mixin;

import io.github.calamech.rloaded.testmods.mixinloadedclass.ItemGreeting;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Adds an interface, a method and a field to {@link Item}, which is loaded long before an add. */
@Mixin(Item.class)
abstract class ItemMixin implements ItemGreeting {
  @Unique private int mixinLoadedClass$calls;

  @Override
  public String mixinLoadedClass$greet() {
    mixinLoadedClass$calls++;
    return "hello #" + mixinLoadedClass$calls;
  }
}
