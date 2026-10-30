package io.github.calamech.rloaded.testmods.contentbasic;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** A test mod that registers a block and its item, the simplest content a mod brings. */
public final class ContentBasic implements ModInitializer {
  private static final Identifier ID = Identifier.fromNamespaceAndPath("content-basic", "basic_block");

  @Override
  public void onInitialize() {
    ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, ID);
    Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey, new Block(BlockBehaviour.Properties.of().setId(blockKey)));
    ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, ID);
    Registry.register(
        BuiltInRegistries.ITEM, itemKey, new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
  }
}
