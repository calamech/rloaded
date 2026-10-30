package io.github.calamech.rloaded.gametest;

import io.github.calamech.rloaded.runtime.engine.BlockEntityCycling;
import io.github.calamech.rloaded.runtime.engine.LateMixins;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Checks that block entities rebuilt after a late Mixin get its fields and keep their data. */
public final class BlockEntityCyclingGameTest {
  private static final BlockPos HERE = new BlockPos(1, 1, 1);

  @GameTest
  public void cycledBlockEntitiesGetInitializedFields(GameTestHelper helper) throws Exception {
    helper.setBlock(HERE, Blocks.CHEST);
    ChestBlockEntity existing = helper.getBlockEntity(HERE, ChestBlockEntity.class);
    existing.setItem(0, new ItemStack(Items.DIAMOND, 3));

    LateMixins.apply("rloaded-gametest.block-entity-init.mixins.json", Agent.instrumentation());

    helper.assertTrue(((FieldInitProbe) existing).rloadedMarker() == null, "the existing chest ran the new field initializer");
    BlockEntityCycling.Result result = BlockEntityCycling.cycle(helper.getLevel());
    ChestBlockEntity rebuilt = helper.getBlockEntity(HERE, ChestBlockEntity.class);
    helper.assertTrue(rebuilt != existing, "the chest was not rebuilt");
    helper.assertTrue(existing.isRemoved(), "the old chest is not marked removed");
    helper.assertValueEqual(((FieldInitProbe) rebuilt).rloadedMarker(), "initialized", "marker of the rebuilt chest");
    helper.assertTrue(ItemStack.matches(rebuilt.getItem(0), new ItemStack(Items.DIAMOND, 3)), "the rebuilt chest lost its items");
    helper.assertTrue(result.rebuilt() > 0, "the cycle reports no rebuilt block entity");
    helper.succeed();
  }

  @GameTest
  public void blockEntityThatCannotBeReadBackStays(GameTestHelper helper) {
    helper.setBlock(HERE, GameTestContent.FRAGILE);
    FragileBlockEntity existing = helper.getBlockEntity(HERE, FragileBlockEntity.class);

    BlockEntityCycling.Result result = BlockEntityCycling.cycle(helper.getLevel());

    helper.assertTrue(result.notRebuilt().contains(helper.absolutePos(HERE)), "the cycle does not report the fragile block entity");
    helper.assertTrue(helper.getBlockEntity(HERE, FragileBlockEntity.class) == existing, "the fragile block entity was replaced");
    helper.assertTrue(!existing.isRemoved(), "the fragile block entity is marked removed");
    helper.succeed();
  }
}
