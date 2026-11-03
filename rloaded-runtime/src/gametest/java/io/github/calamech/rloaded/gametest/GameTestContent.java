package io.github.calamech.rloaded.gametest;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
  private static final Identifier FRAGILE_ID =
      Identifier.fromNamespaceAndPath("rloaded-gametest", "fragile");

  /** A block whose block entity cannot be read back from its saved data. */
  static final Block FRAGILE =
      new FragileBlock(
          BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, FRAGILE_ID)));

  /** The block entity type of {@link #FRAGILE}. */
  static final BlockEntityType<FragileBlockEntity> FRAGILE_BLOCK_ENTITY =
      new BlockEntityType<>(FragileBlockEntity::new, Set.of(FRAGILE));

  /** How many times this mod, there from the start, was told the server started. */
  static final AtomicInteger SERVER_STARTED_CALLS = new AtomicInteger();

  @Override
  public void onInitialize() {
    ServerLifecycleEvents.SERVER_STARTED.register(server -> SERVER_STARTED_CALLS.incrementAndGet());
    Registry.register(BuiltInRegistries.BLOCK, FRAGILE_ID, FRAGILE);
    Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, FRAGILE_ID, FRAGILE_BLOCK_ENTITY);
    PingTraffic.register();
    RegistrySyncCheck.registerPayloads();
  }
}
