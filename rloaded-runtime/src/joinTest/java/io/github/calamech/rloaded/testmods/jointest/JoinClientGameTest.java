package io.github.calamech.rloaded.testmods.jointest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Joins a server running in another process for the first time, accepts its mods on the trust
 * screen, and checks that content-basic and mixin-loaded-class work once in the world, without a
 * restart.
 */
public final class JoinClientGameTest implements FabricClientGameTest {
  private static final Identifier BASIC_BLOCK = Identifier.fromNamespaceAndPath("content-basic", "basic_block");
  private static final int JOIN_TICKS = 6000;

  @Override
  public void runTest(ClientGameTestContext context) {
    String address = required("rloaded.join-test.server");
    Path report = Path.of(required("rloaded.join-test.report"));

    context.runOnClient(
        client ->
            ConnectScreen.startConnecting(
                client.gui.screen(), client, ServerAddress.parseString(address), new ServerData("Join test", address, ServerData.Type.OTHER), false, null));
    context.waitFor(client -> client.gui.screen() != null && client.gui.screen().getClass().getSimpleName().equals("TrustScreen"), JOIN_TICKS);
    context.clickScreenButton("rloaded.trust.install");
    context.waitFor(client -> client.level != null && client.player != null, JOIN_TICKS);

    if (BuiltInRegistries.BLOCK.getOptional(BASIC_BLOCK).isEmpty()) {
      throw new AssertionError("content-basic did not register its block");
    }
    Object greeting = context.computeOnClient(client -> greet(Items.STONE));
    if (!"hello #1".equals(greeting)) {
      throw new AssertionError("stone answers " + greeting + " instead of the greeting of mixin-loaded-class");
    }
    context.runOnClient(client -> client.disconnect(new TitleScreen(), false));
    context.waitFor(client -> client.level == null && client.gui.screen() instanceof TitleScreen);
    try {
      Files.writeString(report, "joined and loaded content-basic and mixin-loaded-class\n", StandardCharsets.UTF_8);
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

  private static Object greet(Item item) {
    try {
      Class<?> greeting = Class.forName("io.github.calamech.rloaded.testmods.mixinloadedclass.ItemGreeting", true, Item.class.getClassLoader());
      return greeting.getMethod("mixinLoadedClass$greet").invoke(item);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("stone does not answer the mixin's method", e);
    }
  }
}
