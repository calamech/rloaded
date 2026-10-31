package io.github.calamech.rloaded.testmods.singleplayertest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.HttpUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The game's side of the singleplayer check, which a harness drives as the launcher: once told,
 * creates a world and opens it to the local network; once told again, closes it to the network and
 * pauses the game, as when the player switches to the launcher; once the mods are added, places
 * content-basic's block and checks that the client shows it with its model, that the client's
 * entities have mixin-field-init's field, and that the world's lockfile lists both mods. Each step
 * reached is a line in the report; the harness writes the steps it allows to the control file.
 */
public final class SingleplayerAddClientGameTest implements FabricClientGameTest {
  private static final Identifier BASIC_BLOCK =
      Identifier.fromNamespaceAndPath("content-basic", "basic_block");
  private static final int STEP_TICKS = 20 * 60 * 5;

  @Override
  public void runTest(ClientGameTestContext context) {
    Path report = Path.of(System.getProperty("rloaded.singleplayer-test.report"));
    Path control = Path.of(System.getProperty("rloaded.singleplayer-test.control"));
    awaitStep(context, control, "open");
    try (TestSingleplayerContext world = context.worldBuilder().create()) {
      world.getConnection().waitForChunksRender();
      IntegratedServer server = context.computeOnClient(Minecraft::getSingleplayerServer);
      boolean published =
          context.computeOnClient(
              client ->
                  server.publishServer(
                      MinecraftServer.MultiplayerScope.LAN, HttpUtil.getAvailablePort()));
      if (!published) {
        throw new AssertionError("the world could not be opened to the local network");
      }
      write(report, "shared");
      awaitStep(context, control, "unshare");
      context.runOnClient(
          client -> {
            server.unpublishServer();
            client.pauseGame(false);
          });
      context.waitFor(Minecraft::isPaused);
      write(report, "paused");
      awaitStep(context, control, "added");
      // Back in the world, the player closes the pause menu if it is still open.
      context.waitFor(
          client ->
              client.level != null
                  && client.player != null
                  && (client.gui.screen() == null || client.gui.screen() instanceof PauseScreen),
          STEP_TICKS);
      context.runOnClient(client -> client.gui.setScreen(null));
      world.getConnection().waitForChunksRender();
      checkBlock(context, world);
      checkEntities(context);
      checkLockfile(server);
      context.takeScreenshot("rloaded-singleplayer-add");
    }
    write(report, "passed");
    write(
        Path.of(System.getProperty("rloaded.client-game-test.report")),
        "mods asked for by the launcher are added to an open singleplayer world");
  }

  private static void checkBlock(ClientGameTestContext context, TestSingleplayerContext world) {
    Block block = BuiltInRegistries.BLOCK.getOptional(BASIC_BLOCK).orElseThrow();
    BlockPos pos =
        context.computeOnClient(
            client -> client.player.blockPosition().relative(client.player.getDirection(), 3));
    world
        .getServer()
        .runCommand(
            "setblock %d %d %d %s".formatted(pos.getX(), pos.getY(), pos.getZ(), BASIC_BLOCK));
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

  private static void awaitStep(ClientGameTestContext context, Path control, String step) {
    context.waitFor(client -> lines(control).contains(step), STEP_TICKS);
  }

  private static List<String> lines(Path file) {
    try {
      return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
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
