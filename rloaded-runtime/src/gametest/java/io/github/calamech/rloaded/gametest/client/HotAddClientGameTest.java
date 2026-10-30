package io.github.calamech.rloaded.gametest.client;

import io.github.calamech.rloaded.gametest.Agent;
import io.github.calamech.rloaded.runtime.engine.HotAdd;
import io.github.calamech.rloaded.runtime.engine.HotAddRefusal;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Checks that mod jars load into the client before a world exists, as when it joins a server that
 * runs them: the mods register their content, their mixins change loaded classes, and a resource
 * reload gives the new block and item their models.
 */
public final class HotAddClientGameTest implements FabricClientGameTest {
  private static final String MODS_PROPERTY = "rloaded.hot-add-mods";
  private static final Identifier BASIC_BLOCK = Identifier.fromNamespaceAndPath("content-basic", "basic_block");
  private static final Identifier NO_ITEM = Identifier.fromNamespaceAndPath("rloaded-gametest", "no_such_item");
  private static final int RELOAD_TICKS = 2400;

  @Override
  public void runTest(ClientGameTestContext context) {
    String jars = System.getProperty(MODS_PROPERTY);
    if (jars == null) {
      throw new AssertionError("run with the " + MODS_PROPERTY + " property");
    }
    List<Path> paths = Arrays.stream(jars.split(File.pathSeparator)).map(Path::of).toList();

    List<String> loaded = context.computeOnClient(client -> load(paths));
    CompletableFuture<Void> reload = context.computeOnClient(Minecraft::reloadResourcePacks);
    context.waitFor(client -> reload.isDone(), RELOAD_TICKS);
    reload.join();

    if (!loaded.stream().sorted().toList().equals(List.of("content-basic", "mixin-loaded-class"))) {
      throw new AssertionError("loaded mods: " + loaded);
    }
    Block block = BuiltInRegistries.BLOCK.getOptional(BASIC_BLOCK).orElseThrow(() -> new AssertionError("no block " + BASIC_BLOCK));
    boolean blockModel =
        context.computeOnClient(
            client -> {
              BlockStateModelSet models = client.getModelManager().getBlockStateModelSet();
              return models.get(block.defaultBlockState()) != models.missingModel();
            });
    if (!blockModel) {
      throw new AssertionError("the new block has the missing model after the resource reload");
    }
    boolean itemModel =
        context.computeOnClient(
            client -> client.getModelManager().getItemModel(BASIC_BLOCK) != client.getModelManager().getItemModel(NO_ITEM));
    if (!itemModel) {
      throw new AssertionError("the new item has the missing model after the resource reload");
    }
    Item stone = Items.STONE;
    Object greeting = context.computeOnClient(client -> greet(stone));
    if (!"hello #1".equals(greeting)) {
      throw new AssertionError("an item that existed before the add answers " + greeting);
    }
    ClientTestReport.passed("mods load on the client before a world exists");
  }

  private static List<String> load(List<Path> jars) {
    try {
      return HotAdd.load(jars, Agent.instrumentation());
    } catch (HotAddRefusal | ReflectiveOperationException e) {
      throw new AssertionError("the test mods did not load", e);
    }
  }

  private static Object greet(Item item) {
    try {
      Class<?> greeting = Class.forName("io.github.calamech.rloaded.testmods.mixinloadedclass.ItemGreeting", true, Item.class.getClassLoader());
      return greeting.getMethod("mixinLoadedClass$greet").invoke(item);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("stone does not answer the mixin's method", e);
    }
  }
}
