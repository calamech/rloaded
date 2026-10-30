package io.github.calamech.rloaded.testmods.jointest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Joins a server running in another process for the first time, reads the trust screen for {@code
 * rloaded.join-test.trust-seconds}, accepts its mods, and checks that content-basic and
 * mixin-loaded-class work once in the world, without a restart. With {@code
 * rloaded.join-test.expect-disconnect}, the downloads are expected to stall: the test then waits to
 * be disconnected and reports the reason.
 */
public final class JoinClientGameTest implements FabricClientGameTest {
  private static final Identifier BASIC_BLOCK =
      Identifier.fromNamespaceAndPath("content-basic", "basic_block");
  private static final int JOIN_TICKS = 6000;
  private static final int TICKS_PER_SECOND = 20;

  @Override
  public void runTest(ClientGameTestContext context) {
    String address = required("rloaded.join-test.server");
    Path report = Path.of(required("rloaded.join-test.report"));

    context.runOnClient(
        client ->
            ConnectScreen.startConnecting(
                client.gui.screen(),
                client,
                ServerAddress.parseString(address),
                new ServerData("Join test", address, ServerData.Type.OTHER),
                false,
                null));
    context.waitFor(
        client ->
            client.gui.screen() != null
                && client.gui.screen().getClass().getSimpleName().equals("TrustScreen"),
        JOIN_TICKS);
    context.waitTicks(
        Integer.parseInt(required("rloaded.join-test.trust-seconds")) * TICKS_PER_SECOND);
    context.clickScreenButton("rloaded.trust.install");
    context.waitFor(client -> screenNamed(client, "InstallScreen"), JOIN_TICKS);
    if (Boolean.getBoolean("rloaded.join-test.expect-disconnect")) {
      context.waitFor(client -> client.gui.screen() instanceof DisconnectedScreen, JOIN_TICKS);
      String reason =
          context.computeOnClient(client -> client.gui.screen().getNarrationMessage().getString());
      write(report, "disconnected: " + reason);
      context.runOnClient(client -> client.gui.setScreen(new TitleScreen()));
      return;
    }
    context.takeScreenshot("rloaded-install-screen");
    context.waitFor(client -> client.level != null && client.player != null, JOIN_TICKS);

    if (BuiltInRegistries.BLOCK.getOptional(BASIC_BLOCK).isEmpty()) {
      throw new AssertionError("content-basic did not register its block");
    }
    Object greeting = context.computeOnClient(client -> greet(Items.STONE));
    if (!"hello #1".equals(greeting)) {
      throw new AssertionError(
          "stone answers " + greeting + " instead of the greeting of mixin-loaded-class");
    }
    context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
    context.waitFor(client -> client.level == null && client.gui.screen() instanceof TitleScreen);
    write(
        report, "joined after the install screen and loaded content-basic and mixin-loaded-class");
  }

  private static boolean screenNamed(Minecraft client, String name) {
    return client.gui.screen() != null
        && client.gui.screen().getClass().getSimpleName().equals(name);
  }

  private static void write(Path report, String line) {
    try {
      Files.writeString(report, line + "\n", StandardCharsets.UTF_8);
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

  // A missing method is what this test checks for: it fails the test, it is not a linkage defect.
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
      throw new AssertionError("stone does not answer the mixin's method", e);
    }
  }
}
