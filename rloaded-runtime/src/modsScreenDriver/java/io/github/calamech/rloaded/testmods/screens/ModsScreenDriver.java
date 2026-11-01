package io.github.calamech.rloaded.testmods.screens;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * Plays a player on the game's Mods screen with the mouse and the keyboard: opens it from the pause
 * menu, types into its search field, clicks Install on a row and reads the row's outcome.
 */
public final class ModsScreenDriver {
  private static final int STEP_TICKS = 20 * 60 * 3;

  private ModsScreenDriver() {}

  /** Whether the pause menu has the Mods button; leaves the pause menu closed. */
  public static boolean hasModsButton(ClientGameTestContext context) {
    context.runOnClient(client -> client.pauseGame(false));
    context.waitFor(client -> client.gui.screen() instanceof PauseScreen);
    boolean found = context.computeOnClient(client -> modsButton(client.gui.screen()).isPresent());
    context.runOnClient(client -> client.gui.setScreen(null));
    return found;
  }

  private static Optional<AbstractWidget> modsButton(Screen screen) {
    return find(screen, widget -> widget instanceof Button && "Mods".equals(text(widget)));
  }

  public static void openModsScreen(ClientGameTestContext context) {
    context.runOnClient(client -> client.pauseGame(false));
    context.waitFor(client -> client.gui.screen() instanceof PauseScreen);
    AbstractWidget mods =
        context
            .computeOnClient(client -> modsButton(client.gui.screen()))
            .orElseThrow(() -> new AssertionError("the pause menu has no Mods button"));
    click(context, mods);
    context.waitFor(client -> isModsScreen(client.gui.screen()));
  }

  public static void search(ClientGameTestContext context, String query) {
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
  public static void install(ClientGameTestContext context, String title, String outcome) {
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
  public static boolean isModsScreen(Screen screen) {
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

  public static long titled(Screen screen, String title) {
    return widgets(screen).stream()
        .filter(widget -> widget instanceof StringWidget && text(widget).startsWith(title))
        .count();
  }

  // Moves the cursor to the middle of [widget], in window coordinates, and clicks, once no resource
  // reload covers the screen: the game ignores the mouse under it.
  private static void click(ClientGameTestContext context, AbstractWidget widget) {
    context.waitFor(client -> client.gui.overlay() == null, STEP_TICKS);
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
}
