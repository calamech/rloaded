package io.github.calamech.rloaded.testmods.addtest;

import io.github.calamech.rloaded.runtime.join.client.JoinClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Joins a server running in another process, which this player already trusts, and stays in the
 * world while the server's admin adds content-basic, then mixin-loaded-class. After each add, it
 * checks that it is back in the world on the same connection, that the mod works, and that a toast
 * named the new mod; after the first, it waits for the new item the server gives; it reports each
 * step in {@code rloaded.add-test.report} and leaves once the file {@code rloaded.add-test.done}
 * exists.
 */
public final class AddClientGameTest implements FabricClientGameTest {
  private static final Identifier BASIC_BLOCK =
      Identifier.fromNamespaceAndPath("content-basic", "basic_block");
  private static final int STEP_TICKS = 20 * 60 * 5;
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded add test");

  /** The log line after which the add check counts errors: what came before is not about adds. */
  private static final String IN_WORLD_MARK = "In the world, before any add";

  @Override
  public void runTest(ClientGameTestContext context) {
    String address = required("rloaded.add-test.server");
    Path report = Path.of(required("rloaded.add-test.report"));
    Path done = Path.of(required("rloaded.add-test.done"));

    context.runOnClient(
        client ->
            ConnectScreen.startConnecting(
                client.gui.screen(),
                client,
                ServerAddress.parseString(address),
                new ServerData("Add test", address, ServerData.Type.OTHER),
                false,
                null));
    context.waitFor(client -> client.level != null && client.player != null, STEP_TICKS);
    Object connection = context.computeOnClient(client -> client.getConnection().getConnection());
    LOGGER.info(IN_WORLD_MARK);
    append(report, "in the world");

    context.waitFor(
        client -> inWorld(client) && BuiltInRegistries.BLOCK.getOptional(BASIC_BLOCK).isPresent(),
        STEP_TICKS);
    Item item = BuiltInRegistries.ITEM.getOptional(BASIC_BLOCK).orElseThrow();
    String stack =
        context.computeOnClient(client -> new ItemStack(item).getHoverName().getString());
    context.waitFor(client -> toastShown(client), STEP_TICKS);
    context.takeScreenshot("rloaded-in-session-add");
    sameConnection(context, connection);
    append(report, "content-basic added; its item is " + stack);

    // The server gives the item once every player has the mod.
    context.waitFor(
        client -> client.player.getInventory().contains(new ItemStack(item)), STEP_TICKS);
    append(report, "content-basic item received");

    context.waitFor(client -> inWorld(client) && "hello #1".equals(greet(Items.STONE)), STEP_TICKS);
    sameConnection(context, connection);
    append(report, "mixin-loaded-class added");

    context.waitFor(client -> Files.exists(done), STEP_TICKS);
    context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
    context.waitFor(client -> client.level == null && client.gui.screen() instanceof TitleScreen);
  }

  private static boolean inWorld(Minecraft client) {
    return client.level != null && client.player != null && client.gui.screen() == null;
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

  // Before the add, the method is missing: that is the state waited out, not a linkage defect.
  @SuppressWarnings("RethrowReflectiveOperationExceptionAsLinkageError")
  private static Object greet(Item item) {
    try {
      Class<?> greeting =
          Class.forName(
              "io.github.calamech.rloaded.testmods.mixinloadedclass.ItemGreeting",
              true,
              Item.class.getClassLoader());
      return greeting.getMethod("mixinLoadedClass$greet").invoke(item);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }
}
