package io.github.calamech.rloaded.testmods.lateitem;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * A test mod that registers one item. Added after another mod's content, it lands in registries
 * that players' games have already synchronized with the server once.
 */
public final class LateItem implements ModInitializer {
  @Override
  public void onInitialize() {
    ResourceKey<Item> key =
        ResourceKey.create(
            Registries.ITEM, Identifier.fromNamespaceAndPath("late-item", "late_item"));
    Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key)));
  }
}
