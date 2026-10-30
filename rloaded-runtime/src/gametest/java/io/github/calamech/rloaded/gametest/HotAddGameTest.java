package io.github.calamech.rloaded.gametest;

import io.github.calamech.rloaded.runtime.engine.HotAdd;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Checks that mod jars the game did not start with load into the running server: content-basic
 * registers a block and its item, and mixin-loaded-class changes {@code Item}, loaded long before.
 */
public final class HotAddGameTest {
  private static final String MODS_PROPERTY = "rloaded.hot-add-mods";
  private static final Identifier BASIC_BLOCK =
      Identifier.fromNamespaceAndPath("content-basic", "basic_block");
  private static final BlockPos HERE = new BlockPos(1, 1, 1);

  @GameTest
  public void modJarsLoadIntoTheRunningGame(GameTestHelper helper) throws Exception {
    String jars = System.getProperty(MODS_PROPERTY);
    helper.assertTrue(jars != null, "run with the " + MODS_PROPERTY + " property");
    List<Path> paths = Arrays.stream(jars.split(File.pathSeparator)).map(Path::of).toList();
    Item stone = Items.STONE;

    List<String> loaded;
    List<String> errors;
    try (ErrorLog log = ErrorLog.open()) {
      loaded = HotAdd.load(paths, Agent.instrumentation());
      errors = log.lines();
    }

    helper.assertValueEqual(errors, List.of(), "error-level log lines");
    helper.assertValueEqual(
        loaded.stream().sorted().toList(),
        List.of("content-basic", "mixin-loaded-class"),
        "loaded mods");
    helper.assertTrue(
        FabricLoader.getInstance().isModLoaded("content-basic"),
        "the loader does not list content-basic");
    helper.assertTrue(
        FabricLoader.getInstance().getModContainer("mixin-loaded-class").isPresent(),
        "the loader has no container for mixin-loaded-class");

    Block block =
        BuiltInRegistries.BLOCK
            .getOptional(BASIC_BLOCK)
            .orElseThrow(() -> new AssertionError("no block " + BASIC_BLOCK));
    Item item =
        BuiltInRegistries.ITEM
            .getOptional(BASIC_BLOCK)
            .orElseThrow(() -> new AssertionError("no item " + BASIC_BLOCK));
    helper.assertValueEqual(block.asItem(), item, "item of the block");
    helper.setBlock(HERE, block);
    helper.assertBlockPresent(block, HERE);

    Class<?> greeting =
        Class.forName(
            "io.github.calamech.rloaded.testmods.mixinloadedclass.ItemGreeting",
            true,
            Item.class.getClassLoader());
    helper.assertTrue(
        greeting.isInstance(stone),
        "an item that existed before the add does not implement ItemGreeting");
    Object first = greeting.getMethod("mixinLoadedClass$greet").invoke(stone);
    Object second = greeting.getMethod("mixinLoadedClass$greet").invoke(stone);
    helper.assertValueEqual(
        List.of(first, second), List.of("hello #1", "hello #2"), "greetings of stone");
    helper.succeed();
  }
}
