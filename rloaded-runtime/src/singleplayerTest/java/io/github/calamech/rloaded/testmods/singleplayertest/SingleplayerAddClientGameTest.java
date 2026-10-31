package io.github.calamech.rloaded.testmods.singleplayertest;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
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
 * Installs mods in an open singleplayer world from the game's Mods screen, the way a player does:
 * opens the pause menu, clicks "Mods", types into the search field and clicks Install. Refused
 * while the world is open to the local network, and for a mod a search still lists but Modrinth
 * removed; then content-basic, and after a second search mixin-field-init, are installed with the
 * game paused. Then places content-basic's block and checks that the client shows it with its
 * model, that the client's entities have mixin-field-init's field, and that the world's lockfile
 * lists both mods.
 */
public final class SingleplayerAddClientGameTest implements FabricClientGameTest {
  private static final Identifier BASIC_BLOCK =
      Identifier.fromNamespaceAndPath("content-basic", "basic_block");
  private static final int STEP_TICKS = 20 * 60 * 3;
  private static final String CONTENT_BASIC = "Content basic";
  private static final String MIXIN_FIELD_INIT = "Mixin field initializer";
  private static final String REMOVED = "removedProject";

  @Override
  public void runTest(ClientGameTestContext context) {
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
      openModsScreen(context);
      search(context, "content");
      install(context, CONTENT_BASIC, "Close the world to LAN players first.");
      context.runOnClient(client -> server.unpublishServer());
      install(context, REMOVED, "Modrinth no longer has this mod.");
      install(context, CONTENT_BASIC, "Installed");
      search(context, "mixin");
      install(context, MIXIN_FIELD_INIT, "Installed");
      // The search result, and the mods the world has.
      context.waitFor(
          client ->
              titled(client.gui.screen(), MIXIN_FIELD_INIT) == 2
                  && titled(client.gui.screen(), CONTENT_BASIC) == 1,
          STEP_TICKS);
      context.takeScreenshot("rloaded-mods-screen");
      context.runOnClient(client -> client.gui.setScreen(null));
      world.getConnection().waitForChunksRender();
      checkBlock(context, world);
      checkEntities(context);
      checkLockfile(server);
      context.takeScreenshot("rloaded-singleplayer-add");
    }
    write(
        Path.of(System.getProperty("rloaded.client-game-test.report")),
        "mods installed from the Mods screen join an open singleplayer world");
  }

  private static void openModsScreen(ClientGameTestContext context) {
    context.runOnClient(client -> client.pauseGame(false));
    context.waitFor(client -> client.gui.screen() instanceof PauseScreen);
    AbstractWidget mods =
        context
            .computeOnClient(
                client ->
                    find(
                        client.gui.screen(),
                        widget -> widget instanceof Button && "Mods".equals(text(widget))))
            .orElseThrow(() -> new AssertionError("the pause menu has no Mods button"));
    click(context, mods);
    context.waitFor(client -> isModsScreen(client.gui.screen()));
  }

  private static void search(ClientGameTestContext context, String query) {
    AbstractWidget field =
        context
            .computeOnClient(client -> find(client.gui.screen(), EditBox.class::isInstance))
            .orElseThrow(() -> new AssertionError("the Mods screen has no search field"));
    click(context, field);
    context.runOnClient(client -> ((EditBox) field).setValue(""));
    context.getInput().typeChars(query);
    context.waitFor(
        client ->
            widgets(client.gui.screen()).stream()
                .anyMatch(
                    widget ->
                        widget instanceof StringWidget
                            && text(widget).toLowerCase(Locale.ROOT).startsWith(query)),
        STEP_TICKS);
  }

  // Clicks Install on the row of [title], then waits until the row shows [outcome]. An add sends
  // the player through the configuration phase; the game opens the Mods screen again after it.
  private static void install(ClientGameTestContext context, String title, String outcome) {
    AbstractWidget button =
        context
            .computeOnClient(client -> rowWidget(client.gui.screen(), title, Button.class))
            .orElseThrow(() -> new AssertionError("no Install button for " + title));
    click(context, button);
    context.waitFor(
        client ->
            isModsScreen(client.gui.screen())
                && rowWidget(client.gui.screen(), title, StringWidget.class)
                    .filter(widget -> outcome.equals(text(widget)))
                    .isPresent(),
        STEP_TICKS);
  }

  // The test mod cannot see the runtime's screen class, which is not public.
  private static boolean isModsScreen(Screen screen) {
    return screen != null && screen.getClass().getSimpleName().equals("ModsScreen");
  }

  // The nearest widget of [type] to the right of the first row whose text starts with [title].
  private static Optional<AbstractWidget> rowWidget(
      Screen screen, String title, Class<? extends AbstractWidget> type) {
    return find(screen, widget -> widget instanceof StringWidget && text(widget).startsWith(title))
        .flatMap(
            name ->
                widgets(screen).stream()
                    .filter(widget -> widget.getY() == name.getY() && widget.getX() > name.getX())
                    .filter(type::isInstance)
                    .min(Comparator.comparingInt(AbstractWidget::getX)));
  }

  private static long titled(Screen screen, String title) {
    return widgets(screen).stream()
        .filter(widget -> widget instanceof StringWidget && text(widget).startsWith(title))
        .count();
  }

  // Moves the cursor to the middle of [widget], in window coordinates, and clicks.
  private static void click(ClientGameTestContext context, AbstractWidget widget) {
    double scale =
        context.computeOnClient(
            client ->
                client.getWindow().getScreenWidth()
                    / (double) client.getWindow().getGuiScaledWidth());
    context
        .getInput()
        .setCursorPos(
            (widget.getX() + widget.getWidth() / 2.0) * scale,
            (widget.getY() + widget.getHeight() / 2.0) * scale);
    context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
  }

  private static Optional<AbstractWidget> find(Screen screen, Predicate<AbstractWidget> wanted) {
    return widgets(screen).stream().filter(wanted).findFirst();
  }

  private static List<AbstractWidget> widgets(Screen screen) {
    List<AbstractWidget> found = new ArrayList<>();
    if (screen != null) {
      collect(screen, found);
    }
    return found;
  }

  private static void collect(ContainerEventHandler container, List<AbstractWidget> found) {
    for (GuiEventListener child : container.children()) {
      if (child instanceof AbstractWidget widget) {
        found.add(widget);
      }
      if (child instanceof ContainerEventHandler nested) {
        collect(nested, found);
      }
    }
  }

  private static String text(AbstractWidget widget) {
    return widget.getMessage().getString();
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
