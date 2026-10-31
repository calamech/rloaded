package io.github.calamech.rloaded.runtime.staging;

import java.util.List;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** What an add brought to the registries a staging server exercises. */
record NewContent(List<Block> blocks, List<EntityType<?>> entities, List<Item> items) {
  static final NewContent NONE = new NewContent(List.of(), List.of(), List.of());
}
