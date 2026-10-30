package io.github.calamech.rloaded.gametest;

import java.util.Set;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Registers the blocks the game tests place, and the packets other mods would send. */
public final class GameTestContent implements ModInitializer {
  private static final Identifier FRAGILE_ID = Identifier.fromNamespaceAndPath("rloaded-gametest", "fragile");

  /** A block whose block entity cannot be read back from its saved data. */
  static final Block FRAGILE =
      new FragileBlock(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, FRAGILE_ID)));

  /** The block entity type of {@link #FRAGILE}. */
  static final BlockEntityType<FragileBlockEntity> FRAGILE_BLOCK_ENTITY =
      new BlockEntityType<>(FragileBlockEntity::new, Set.of(FRAGILE));

  @Override
  public void onInitialize() {
    Registry.register(BuiltInRegistries.BLOCK, FRAGILE_ID, FRAGILE);
    Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, FRAGILE_ID, FRAGILE_BLOCK_ENTITY);
    PingTraffic.register();
  }
}
