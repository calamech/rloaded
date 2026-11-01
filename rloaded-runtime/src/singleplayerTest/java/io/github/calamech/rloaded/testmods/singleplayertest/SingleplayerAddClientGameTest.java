package io.github.calamech.rloaded.testmods.singleplayertest;

import io.github.calamech.rloaded.testmods.screens.ModsScreenDriver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldBuilder;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The game's side of the singleplayer check, in three games. The first installs mods in an open
 * singleplayer world from the game's Mods screen, the way a player does: opens the pause menu,
 * clicks "Mods", types into the search field and clicks Install. Refused while the world is open to
 * the local network, and for a mod a search still lists but Modrinth removed; then content-basic,
 * and after a second search mixin-field-init, are installed with the game paused. Then places
 * content-basic's block and checks that the client shows it with its model, that the client's
 * entities have mixin-field-init's field, and that the world's lockfile lists both mods; it opens
 * the world again and finds the block. The second, a fresh game, opens the world, which installs
 * its mods first, finds the block, marks mixin-field-init for removal from the Mods screen, then
 * opens a world without mods and starts again. The third opens that world by itself, without the
 * mods, then opens the first world, which loses mixin-field-init after a backup.
 */
public final class SingleplayerAddClientGameTest implements FabricClientGameTest {
  private static final Identifier BASIC_BLOCK =
      Identifier.fromNamespaceAndPath("content-basic", "basic_block");
  private static final int STEP_TICKS = 20 * 60 * 3;
  private static final String CONTENT_BASIC = "Content basic";
  private static final String MIXIN_FIELD_INIT = "Mixin field initializer";
  private static final String REMOVED = "removedProject";
  private static final String ISLAND = "Island";
  private static final String PLAIN = "Plain";
  private static final int WORLD_TICKS = 20;

  @Override
  public void runTest(ClientGameTestContext context) {
    Path state = Path.of(System.getProperty("rloaded.singleplayer-test.state"));
    switch (System.getProperty("rloaded.singleplayer-test.step")) {
      case "first" -> first(context, state);
      case "reopen" -> reopen(context, state);
      case "restarted" -> restarted(context, state);
      default -> throw new IllegalStateException("unknown step");
    }
  }

  // Adds the mods to Island from the Mods screen and places a block, then opens Island again in
  // the same game, which needs nothing more. Plain, made first, has no mods.
  private static void first(ClientGameTestContext context, Path state) {
    try (TestSingleplayerContext plain = named(context, PLAIN).create()) {
      plain.getConnection().waitForChunksRender();
    }
    TestWorldSave save;
    BlockPos pos;
    try (TestSingleplayerContext world = named(context, ISLAND).create()) {
      save = world.getWorldSave();
      world.getConnection().waitForChunksRender();
      IntegratedServer server = context.computeOnClient(Minecraft::getSingleplayerServer);
      addFromTheScreen(context, server);
      world.getConnection().waitForChunksRender();
      pos = placeBlock(context, world);
      checkEntities(context);
      checkLockfile(server);
      context.takeScreenshot("rloaded-singleplayer-add");
    }
    try (TestSingleplayerContext again = save.open()) {
      again.getConnection().waitForChunksRender();
      checkBlockAt(context, pos);
    }
    write(state, pos.getX() + " " + pos.getY() + " " + pos.getZ());
    passed("mods installed from the Mods screen join an open singleplayer world");
  }

  private static void addFromTheScreen(ClientGameTestContext context, IntegratedServer server) {
    boolean published =
        context.computeOnClient(
            client ->
                server.publishServer(
                    MinecraftServer.MultiplayerScope.LAN, HttpUtil.getAvailablePort()));
    if (!published) {
      throw new AssertionError("the world could not be opened to the local network");
    }
    ModsScreenDriver.openModsScreen(context);
    ModsScreenDriver.search(context, "content");
    ModsScreenDriver.press(context, CONTENT_BASIC, "Close the world to LAN players first.");
    context.runOnClient(client -> server.unpublishServer());
    ModsScreenDriver.press(context, REMOVED, "Modrinth no longer has this mod.");
    ModsScreenDriver.press(context, CONTENT_BASIC, "Installed");
    ModsScreenDriver.search(context, "mixin");
    ModsScreenDriver.press(context, MIXIN_FIELD_INIT, "Installed");
    // The search result, and the mods the world has.
    context.waitFor(
        client ->
            ModsScreenDriver.titled(client.gui.screen(), MIXIN_FIELD_INIT) == 2
                && ModsScreenDriver.titled(client.gui.screen(), CONTENT_BASIC) == 1,
        STEP_TICKS);
    context.takeScreenshot("rloaded-mods-screen");
    context.runOnClient(client -> client.gui.setScreen(null));
  }

  // A fresh game opens Island: it installs the world's mods before the world loads, so the block
  // is still there. Then it opens Plain, which does not list them: the game must start again.
  private static void reopen(ClientGameTestContext context, Path state) {
    List<Integer> xyz = Arrays.stream(read(state).split(" ", -1)).map(Integer::parseInt).toList();
    BlockPos pos = new BlockPos(xyz.get(0), xyz.get(1), xyz.get(2));
    open(context, ISLAND);
    checkBlockAt(context, pos);
    checkEntities(context);
    // The owner marks mixin-field-init for removal; it leaves when the world opens next.
    ModsScreenDriver.openModsScreen(context);
    ModsScreenDriver.search(context, "mixin");
    ModsScreenDriver.press(context, MIXIN_FIELD_INIT, "Removed at next restart");
    context.runOnClient(client -> client.gui.setScreen(null));
    leave(context);
    passed("a fresh game installs the mods of a world it opens");
    context.runOnClient(client -> client.createWorldOpenFlows().openWorld(PLAIN, () -> {}));
    try {
      context.waitFor(client -> false, STEP_TICKS);
    } catch (IllegalStateException stopped) {
      // Fabric's test runner refuses to reach a game that stopped, and fails the run if the test
      // goes on: this thread waits for the process to end with the game's own exit code.
      waitForTheEnd();
    }
  }

  // The game started again opens Plain by itself, without the mods it had loaded for Island. Then
  // it opens Island, whose marked mod leaves first, after a backup of the world.
  private static void restarted(ClientGameTestContext context, Path state) {
    context.waitFor(
        client -> client.level != null && client.player != null && client.gui.screen() == null,
        STEP_TICKS);
    String name =
        context.computeOnClient(
            client -> client.getSingleplayerServer().getWorldData().getLevelName());
    if (!PLAIN.equals(name)) {
      throw new AssertionError("the game opened " + name + " after its restart");
    }
    if (BuiltInRegistries.BLOCK.getOptional(BASIC_BLOCK).isPresent()) {
      throw new AssertionError("the restarted game still has content-basic");
    }
    leave(context);
    passed("a game with mods a world does not list starts again to open it");

    List<Integer> xyz = Arrays.stream(read(state).split(" ", -1)).map(Integer::parseInt).toList();
    open(context, ISLAND);
    checkBlockAt(context, new BlockPos(xyz.get(0), xyz.get(1), xyz.get(2)));
    if (FabricLoader.getInstance().isModLoaded("mixin-field-init")) {
      throw new AssertionError("mixin-field-init is still loaded after its removal");
    }
    Path world =
        context
            .computeOnClient(Minecraft::getSingleplayerServer)
            .getWorldPath(LevelResource.ROOT)
            .resolve("rloaded");
    try {
      String lockfile = Files.readString(world.resolve("lockfile.json"), StandardCharsets.UTF_8);
      if (lockfile.contains("mfiProject") || !lockfile.contains("cbProject")) {
        throw new AssertionError("the world's lockfile after the removal: " + lockfile);
      }
      try (var backups = Files.list(world.resolve("backups"))) {
        if (backups.noneMatch(file -> file.getFileName().toString().endsWith(".zip"))) {
          throw new AssertionError("the world was not saved before the removal");
        }
      }
    } catch (IOException e) {
      throw new AssertionError("the world's files cannot be read", e);
    }
    leave(context);
    passed("a mod removed from the Mods screen leaves the world when it opens next");
  }

  private static TestWorldBuilder named(ClientGameTestContext context, String name) {
    return context.worldBuilder().adjustSettings(ui -> ui.setName(name));
  }

  private static void open(ClientGameTestContext context, String levelId) {
    context.runOnClient(
        client ->
            client
                .createWorldOpenFlows()
                .openWorld(
                    levelId,
                    () -> {
                      throw new AssertionError("opening " + levelId + " was cancelled");
                    }));
    context.waitFor(
        client -> client.level != null && client.player != null && client.gui.screen() == null,
        STEP_TICKS);
    context.waitTicks(WORLD_TICKS);
  }

  // Leaves the world and waits for its server's thread to end, as Fabric's own contexts do.
  private static void leave(ClientGameTestContext context) {
    MinecraftServer server = context.computeOnClient(Minecraft::getSingleplayerServer);
    context.runOnClient(
        client -> client.disconnect(new GenericMessageScreen(Component.literal("")), false));
    context.waitFor(
        client -> client.level == null && !server.getRunningThread().isAlive(), STEP_TICKS);
    context.setScreen(TitleScreen::new);
  }

  private static void waitForTheEnd() {
    try {
      new CountDownLatch(1).await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static BlockPos placeBlock(ClientGameTestContext context, TestSingleplayerContext world) {
    BlockPos pos =
        context.computeOnClient(
            client -> client.player.blockPosition().relative(client.player.getDirection(), 3));
    world
        .getServer()
        .runCommand(
            "setblock %d %d %d %s".formatted(pos.getX(), pos.getY(), pos.getZ(), BASIC_BLOCK));
    checkBlockAt(context, pos);
    return pos;
  }

  private static void checkBlockAt(ClientGameTestContext context, BlockPos pos) {
    Block block =
        BuiltInRegistries.BLOCK
            .getOptional(BASIC_BLOCK)
            .orElseThrow(() -> new AssertionError("content-basic's block is not registered"));
    context.waitFor(client -> client.level.getBlockState(pos).is(block), STEP_TICKS);
    boolean model =
        context.computeOnClient(
            client -> {
              BlockStateModelSet models = client.getModelManager().getBlockStateModelSet();
              return !models.get(block.defaultBlockState()).equals(models.missingModel());
            });
    if (!model) {
      throw new AssertionError("the new block has the missing model");
    }
  }

  // The client's entities were built before mixin-field-init came: the player's game rebuilt its
  // world when it went through the configuration phase.
  private static void checkEntities(ClientGameTestContext context) {
    long missing =
        context.computeOnClient(
            client -> {
              long count = 0;
              for (Entity entity : client.level.entitiesForRendering()) {
                if (marker(entity) == null) {
                  count++;
                }
              }
              return count;
            });
    if (missing != 0) {
      throw new AssertionError(missing + " client entities lack mixin-field-init's field");
    }
  }

  // The test mod cannot see mixin-field-init's classes, which were not loaded when it started.
  private static Object marker(Entity entity) {
    try {
      return entity.getClass().getMethod("mixinFieldInit$marker").invoke(entity);
    } catch (ReflectiveOperationException e) {
      throw new LinkageError("entities lack mixin-field-init's method", e);
    }
  }

  private static void checkLockfile(IntegratedServer server) {
    Path lockfile =
        server.getWorldPath(LevelResource.ROOT).resolve("rloaded").resolve("lockfile.json");
    String text;
    try {
      text = Files.readString(lockfile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new AssertionError("the world has no lockfile at " + lockfile, e);
    }
    if (!text.contains("cbProject") || !text.contains("mfiProject")) {
      throw new AssertionError("the world's lockfile lacks the added mods: " + text);
    }
  }

  private static void passed(String test) {
    write(Path.of(System.getProperty("rloaded.client-game-test.report")), test);
  }

  private static String read(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void write(Path file, String line) {
    try {
      Files.writeString(
          file,
          line + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
