package io.github.calamech.rloaded.testmods.addtest;

import io.github.calamech.rloaded.runtime.join.client.JoinClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.CodeOfConductScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Joins a server running in another process, which this player already trusts, and stays in the
 * world while the server's admin adds mods. {@code rloaded.add-test.steps} lists what to wait for,
 * in order: content-basic (with a toast naming it), its item given by the server, mixin-field-init,
 * keybind-and-tab, or the id of a generated mod. After each step it checks that it is still on the
 * same connection and reports the step in {@code rloaded.add-test.report}; it leaves once the file
 * {@code rloaded.add-test.done} exists.
 */
public final class AddClientGameTest implements FabricClientGameTest {
  private static final Identifier BASIC_BLOCK =
      Identifier.fromNamespaceAndPath("content-basic", "basic_block");
  private static final int STEP_TICKS = 20 * 60 * 5;
  private static final int MIN_ENTITIES = 3;
  private static final Identifier LATE_ITEM =
      Identifier.fromNamespaceAndPath("late-item", "late_item");
  private static final Identifier KEYBIND_AND_TAB =
      Identifier.fromNamespaceAndPath("keybind-and-tab", "tab");
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded add test");
  private static final String CODE_OF_CONDUCT = "the code of conduct";
  private static final String REJOIN = "rejoin";
  private static final String RESOURCE_PACK = "the resource pack";

  /** The log line after which the add check counts errors: what came before is not about adds. */
  private static final String IN_WORLD_MARK = "In the world, before any add";

  @Override
  public void runTest(ClientGameTestContext context) {
    String address = required("rloaded.add-test.server");
    Path report = Path.of(required("rloaded.add-test.report"));
    Path done = Path.of(required("rloaded.add-test.done"));

    connect(context, address);
    String answered = answerAtJoin(context);
    Object connection = connection(context);
    LOGGER.info(IN_WORLD_MARK);
    append(report, answered.isEmpty() ? "in the world" : "in the world; answered " + answered);

    for (String step : required("rloaded.add-test.steps").split(",", -1)) {
      if (step.equals(REJOIN)) {
        rejoin(context, address);
      }
      String details = step(context, step);
      sameConnection(context, connection);
      append(report, details.isEmpty() ? step : step + "; " + details);
    }

    context.waitFor(client -> Files.exists(done), STEP_TICKS);
    context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
    context.waitFor(client -> client.level == null && client.gui.screen() instanceof TitleScreen);
  }

  // Waits until the step is done, and says what it saw.
  private static String step(ClientGameTestContext context, String step) {
    return switch (step) {
      case "content-basic" -> {
        context.waitFor(
            client ->
                inWorld(client) && BuiltInRegistries.BLOCK.getOptional(BASIC_BLOCK).isPresent(),
            STEP_TICKS);
        Item item = BuiltInRegistries.ITEM.getOptional(BASIC_BLOCK).orElseThrow();
        String stack =
            context.computeOnClient(client -> new ItemStack(item).getHoverName().getString());
        context.waitFor(client -> toastShown(client), STEP_TICKS);
        context.takeScreenshot("rloaded-in-session-add");
        yield "its item is " + stack;
      }
      // The server gives the item once every player has the mod.
      case "content-basic-item" -> {
        Item item = BuiltInRegistries.ITEM.getOptional(BASIC_BLOCK).orElseThrow();
        context.waitFor(
            client -> inWorld(client) && client.player.getInventory().contains(new ItemStack(item)),
            STEP_TICKS);
        yield "";
      }
      case "mixin-field-init" -> {
        context.waitFor(client -> inWorld(client) && everyEntityInitialized(client), STEP_TICKS);
        yield "";
      }
      case "late-item" -> {
        context.waitFor(
            client -> inWorld(client) && BuiltInRegistries.ITEM.getOptional(LATE_ITEM).isPresent(),
            STEP_TICKS);
        yield "";
      }
      // A game started again after the rejoin: both items came back with the inventory.
      case "rejoined" -> {
        context.waitFor(
            client ->
                inWorld(client)
                    && Stream.of(BASIC_BLOCK, LATE_ITEM)
                        .allMatch(
                            id ->
                                BuiltInRegistries.ITEM
                                    .getOptional(id)
                                    .filter(
                                        item ->
                                            client
                                                .player
                                                .getInventory()
                                                .contains(new ItemStack(item)))
                                    .isPresent()),
            STEP_TICKS);
        yield "";
      }
      case "late-item-given" -> {
        context.waitFor(
            client ->
                inWorld(client)
                    && BuiltInRegistries.ITEM
                        .getOptional(LATE_ITEM)
                        .filter(item -> client.player.getInventory().contains(new ItemStack(item)))
                        .isPresent(),
            STEP_TICKS);
        yield "";
      }
      case "keybind-and-tab" -> {
        context.waitFor(
            client -> inWorld(client) && hasKeyMapping(client) && hasTab(client), STEP_TICKS);
        yield "";
      }
      // A generated mod without code: the loader lists it once it is added.
      default -> {
        context.waitFor(
            client -> inWorld(client) && FabricLoader.getInstance().isModLoaded(step), STEP_TICKS);
        yield "";
      }
    };
  }

  private static void connect(ClientGameTestContext context, String address) {
    context.runOnClient(
        client ->
            ConnectScreen.startConnecting(
                client.gui.screen(),
                client,
                ServerAddress.parseString(address),
                new ServerData("Add test", address, ServerData.Type.OTHER),
                false,
                null));
  }

  private static Object connection(ClientGameTestContext context) {
    return context.computeOnClient(client -> client.getConnection().getConnection());
  }

  // Leaves the server for the title screen and joins it again in the same game, which loaded mods
  // while it played there: the game must restart instead, and the check starts it again.
  private static void rejoin(ClientGameTestContext context, String address) {
    context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
    context.waitFor(client -> client.level == null && client.gui.screen() instanceof TitleScreen);
    boolean registered =
        context.computeOnClient(
            client -> BuiltInRegistries.ITEM.getOptional(LATE_ITEM).isPresent());
    LOGGER.info("Left the server; late-item registered: {}", registered);
    connect(context, address);
    context.waitFor(client -> false, STEP_TICKS);
  }

  // Accepts the server's code of conduct and resource pack if it has them, and says which it did.
  private static String answerAtJoin(ClientGameTestContext context) {
    List<String> answered = new ArrayList<>();
    while (true) {
      context.waitFor(
          client ->
              (client.level != null && client.player != null && client.gui.screen() == null)
                  || question(client) != null,
          STEP_TICKS);
      String question = context.computeOnClient(AddClientGameTest::question);
      if (question == null) {
        return String.join(" and ", answered);
      }
      context.clickScreenButton(question.equals(CODE_OF_CONDUCT) ? "gui.acknowledge" : "gui.yes");
      answered.add(question);
    }
  }

  // An add must not ask again what the player answered when it joined.
  private static boolean inWorld(Minecraft client) {
    String question = question(client);
    if (question != null) {
      throw new AssertionError("the player was asked about " + question + " again");
    }
    return client.level != null && client.player != null && client.gui.screen() == null;
  }

  private static String question(Minecraft client) {
    Screen screen = client.gui.screen();
    if (screen instanceof CodeOfConductScreen) {
      return CODE_OF_CONDUCT;
    }
    return screen instanceof ConfirmScreen ? RESOURCE_PACK : null;
  }

  // In the options' list, which the controls screen shows and options.txt saves.
  private static boolean hasKeyMapping(Minecraft client) {
    return Arrays.stream(client.options.keyMappings)
        .anyMatch(mapping -> mapping.getName().equals("key.keybind-and-tab.wave"));
  }

  // Listed among the creative tabs, with its item, once the tabs are built for this world.
  private static boolean hasTab(Minecraft client) {
    CreativeModeTabs.tryRebuildTabContents(
        client.player.connection.enabledFeatures(), true, client.level.registryAccess());
    return BuiltInRegistries.CREATIVE_MODE_TAB
        .getOptional(KEYBIND_AND_TAB)
        .filter(
            tab ->
                CreativeModeTabs.allTabs().contains(tab)
                    && tab.getDisplayItems().stream().anyMatch(stack -> stack.is(Items.DIAMOND)))
        .isPresent();
  }

  private static boolean toastShown(Minecraft client) {
    return client.gui.toastManager().getToast(SystemToast.class, JoinClient.ADDED_TOAST) != null;
  }

  // An add keeps the player on its connection: a reconnection would hide a failed reconfiguration.
  @SuppressWarnings("ReferenceEquality")
  private static void sameConnection(ClientGameTestContext context, Object connection) {
    boolean same =
        context.computeOnClient(
            client ->
                client.getConnection() != null
                    && client.getConnection().getConnection() == connection);
    if (!same) {
      throw new AssertionError("the client reconnected during the add");
    }
  }

  private static void append(Path report, String line) {
    try {
      Files.writeString(
          report,
          line + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String required(String property) {
    String value = System.getProperty(property);
    if (value == null) {
      throw new IllegalStateException("run with the " + property + " property");
    }
    return value;
  }

  // The client's entities are new objects after the reconfiguration: each has the mixin's field.
  // Before the add, the interface is missing: that is the state waited out, not a linkage defect.
  @SuppressWarnings("RethrowReflectiveOperationExceptionAsLinkageError")
  private static boolean everyEntityInitialized(Minecraft client) {
    try {
      Class<?> marker =
          Class.forName(
              "io.github.calamech.rloaded.testmods.mixinfieldinit.FieldInitMarker",
              true,
              Entity.class.getClassLoader());
      Method read = marker.getMethod("mixinFieldInit$marker");
      int entities = 0;
      for (Entity entity : client.level.entitiesForRendering()) {
        if (!"initialized".equals(read.invoke(entity))) {
          return false;
        }
        entities++;
      }
      // The two players and the armor stand summoned before the adds.
      return entities >= MIN_ENTITIES;
    } catch (ReflectiveOperationException e) {
      return false;
    }
  }
}
