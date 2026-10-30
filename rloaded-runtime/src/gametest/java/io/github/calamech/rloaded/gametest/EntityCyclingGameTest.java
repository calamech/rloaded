package io.github.calamech.rloaded.gametest;

import io.github.calamech.rloaded.runtime.engine.EntityCycling;
import io.github.calamech.rloaded.runtime.engine.LateMixins;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.vehicle.minecart.Minecart;

/**
 * Checks that entities rebuilt from storage after a late Mixin get the fields it adds, with their
 * initial values.
 */
public final class EntityCyclingGameTest {
  @GameTest
  public void cycledEntitiesGetInitializedFields(GameTestHelper helper) throws Exception {
    ArmorStand existing = helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(1, 1, 1));
    ArmorStand passenger = helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(3, 1, 1));
    Minecart vehicle = helper.spawn(EntityTypes.MINECART, new BlockPos(3, 1, 1));
    helper.assertTrue(passenger.startRiding(vehicle, true, false), "the armor stand did not get into the minecart");

    LateMixins.apply("rloaded-gametest.field-init.mixins.json", Agent.instrumentation());

    helper.assertTrue(((FieldInitProbe) existing).rloadedMarker() == null, "the existing armor stand ran the new field initializer");
    long[] cycled = EntityCycling.cycle(helper.getLevel());
    helper.assertTrue(cycled.length > 0, "no chunk was cycled");
    helper.assertTrue(existing.isRemoved(), "the existing armor stand is still in the level");
    helper.assertTrue(passenger.isRemoved(), "the existing passenger is still in the level");
    helper.succeedWhen(
        () -> {
          helper.assertTrue(EntityCycling.isComplete(helper.getLevel(), cycled), "the cycled entities are not back yet");
          List<ArmorStand> standsHere = helper.getEntities(EntityTypes.ARMOR_STAND);
          helper.assertValueEqual(standsHere.size(), 2, "armor stands in the test area");
          helper.assertTrue(!standsHere.contains(existing) && !standsHere.contains(passenger), "an armor stand was not rebuilt");
          helper.assertValueEqual(
              standsHere.stream().filter(stand -> stand.getVehicle() instanceof Minecart).count(), 1L, "armor stands riding a minecart");
          List<String> markers =
              helper.getLevel().getEntities(EntityTypes.ARMOR_STAND, stand -> true).stream()
                  .map(stand -> String.valueOf(((FieldInitProbe) stand).rloadedMarker()))
                  .distinct()
                  .toList();
          helper.assertValueEqual(markers, List.of("initialized"), "markers of every armor stand in the level");
        });
  }
}
