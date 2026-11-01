package io.github.calamech.rloaded.runtime.join.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.ServerReconfigScreen;
import net.minecraft.network.chat.Component;

/**
 * Warns the player that another key answers at an address where they trusted a server. "Leave" has
 * the focus; "Trust the new identity" goes on to the trust screen, as for a new server.
 */
final class KeyChangedScreen extends Screen {
  private static final int HEADER_HEIGHT = 48;
  private static final int SPACING = 4;
  private static final int MESSAGE_WIDTH = 320;
  private static final int HEARTBEAT_TICKS = 100;

  private final Screen parent;
  private final Runnable onTrust;
  private final Runnable onLeave;
  private final Runnable heartbeat;
  // Made again at each init: a layout keeps every child added to it.
  private HeaderAndFooterLayout layout;
  private Button leave;
  private int ticks;

  KeyChangedScreen(
      Screen parent, String previousName, Runnable onTrust, Runnable onLeave, Runnable heartbeat) {
    super(Component.translatable("rloaded.key_changed.title", previousName));
    this.parent = parent;
    this.onTrust = onTrust;
    this.onLeave = onLeave;
    this.heartbeat = heartbeat;
  }

  @Override
  protected void init() {
    layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, HEADER_HEIGHT);
    layout.addToHeader(new StringWidget(getTitle(), font));
    layout.addToContents(
        new MultiLineTextWidget(Component.translatable("rloaded.key_changed.message"), font)
            .setMaxWidth(MESSAGE_WIDTH)
            .setCentered(true));
    LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(SPACING * 2));
    leave =
        footer.addChild(
            Button.builder(
                    Component.translatable("rloaded.key_changed.leave"), button -> onLeave.run())
                .build());
    footer.addChild(
        Button.builder(Component.translatable("rloaded.key_changed.trust"), button -> onTrust.run())
            .build());
    layout.visitWidgets(this::addRenderableWidget);
    repositionElements();
  }

  @Override
  protected void setInitialFocus() {
    setInitialFocus(leave);
  }

  @Override
  protected void repositionElements() {
    layout.arrangeElements();
  }

  @Override
  public boolean shouldCloseOnEsc() {
    return false;
  }

  // The connection is ticked by the screen it was opened from; keep ticking it while the player
  // reads. The server stops waiting for a join without progress; reading this screen is progress.
  @Override
  public void tick() {
    super.tick();
    ticks++;
    if (ticks % HEARTBEAT_TICKS == 0) {
      heartbeat.run();
    }
    if (parent instanceof ConnectScreen || parent instanceof ServerReconfigScreen) {
      parent.tick();
    }
  }
}
