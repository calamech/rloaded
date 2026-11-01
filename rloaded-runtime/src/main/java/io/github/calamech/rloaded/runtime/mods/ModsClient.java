package io.github.calamech.rloaded.runtime.mods;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.calamech.rloaded.admin.screen.ModsBrowser;
import io.github.calamech.rloaded.protocol.AdminEvent;
import io.github.calamech.rloaded.protocol.AdminRequest;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.ModrinthAccess;
import io.github.calamech.rloaded.runtime.admin.AdminPayloads;
import kotlin.Unit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's side of the Mods screen: a "Mods" button in the pause menu and a key, unbound by
 * default, both there only when the server said the player may add mods. Requests go to the server
 * over {@link AdminPayloads}; its answers go to the screen. The search and the rows live as long as
 * the game, so a player finds them again after the configuration phase an add goes through; an
 * answer that arrives with no screen open opens it again to show the outcome.
 */
public final class ModsClient {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");
  private static final int BUTTON_WIDTH = 98;
  private static final int BUTTON_HEIGHT = 20;
  private static final int MARGIN = 6;
  // Whether the server of this connection lets the player add mods; it says so at each join.
  private static volatile boolean allowed;
  private static ModsBrowser browser;
  // The client thread is the only one to touch it.
  private static boolean reopen;

  private ModsClient() {}

  /** Registers the button, the key and the channel; the client calls it once. */
  public static void register() {
    KeyMapping.Category category =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("rloaded", "rloaded"));
    KeyMapping key =
        KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.rloaded.mods", InputConstants.UNKNOWN.getValue(), category));
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          while (key.consumeClick()) {
            if (allowed && client.gui.screen() == null) {
              open(client, null);
            }
          }
          if (reopen && client.level != null && client.gui.screen() == null) {
            reopen = false;
            open(client, null);
          }
        });
    ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> allowed = false);
    ScreenEvents.AFTER_INIT.register(
        (client, screen, width, height) -> {
          if (screen instanceof PauseScreen && allowed) {
            Screens.getWidgets(screen)
                .add(
                    Button.builder(
                            Component.translatable("rloaded.mods.button"),
                            button -> open(client, screen))
                        .bounds(MARGIN, MARGIN, BUTTON_WIDTH, BUTTON_HEIGHT)
                        .build());
          }
        });
    ClientPlayNetworking.registerGlobalReceiver(
        AdminPayloads.Event.TYPE,
        (payload, context) -> context.client().execute(() -> receive(context.client(), payload)));
  }

  private static void receive(Minecraft client, AdminPayloads.Event payload) {
    switch (payload.decode()) {
      case Outcome.Err<?> err ->
          LOGGER.warn("The server sent a Mods event that cannot be read: {}", err.getError());
      case Outcome.Ok<?> ok -> {
        AdminEvent event = (AdminEvent) ok.getValue();
        if (event instanceof AdminEvent.Access access) {
          allowed = access.getAllowed();
          return;
        }
        boolean answer = browser().answers(event);
        browser().onEvent(event);
        if (answer && !(client.gui.screen() instanceof ModsScreen)) {
          reopen = true;
        }
      }
    }
  }

  private static void open(Minecraft client, Screen parent) {
    client.gui.setScreen(new ModsScreen(parent, browser()));
  }

  // The client thread is the only one to call it.
  private static ModsBrowser browser() {
    if (browser == null) {
      browser =
          new ModsBrowser(
              ModrinthAccess.open().modrinth(),
              SharedConstants.getCurrentVersion().name(),
              request -> {
                send(request);
                return Unit.INSTANCE;
              },
              view -> Unit.INSTANCE,
              line -> {
                LOGGER.info("Mods screen: {}", line);
                return Unit.INSTANCE;
              });
    }
    return browser;
  }

  private static void send(AdminRequest request) {
    Minecraft.getInstance()
        .execute(() -> ClientPlayNetworking.send(AdminPayloads.Request.of(request)));
  }
}
