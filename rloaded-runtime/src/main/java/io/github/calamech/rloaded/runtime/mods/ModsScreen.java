package io.github.calamech.rloaded.runtime.mods;

import io.github.calamech.rloaded.admin.screen.InstallStatus;
import io.github.calamech.rloaded.admin.screen.ModRow;
import io.github.calamech.rloaded.admin.screen.ModsBrowser;
import io.github.calamech.rloaded.admin.screen.ModsView;
import io.github.calamech.rloaded.admin.screen.SearchView;
import io.github.calamech.rloaded.protocol.AddProblem;
import java.text.NumberFormat;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Searches Modrinth and adds mods to the server the player is on, the world's own server in
 * singleplayer: the results with their author and downloads and an Install button, then the mods
 * the server has. Each mod's progress and outcome show on its row.
 */
final class ModsScreen extends Screen {
  private static final int HEADER_HEIGHT = 56;
  private static final int SPACING = 4;
  private static final int SEARCH_WIDTH = 240;
  private static final int SEARCH_HEIGHT = 20;
  private static final int MARGIN = 20;
  private static final int STATUS_WIDTH = 120;
  private static final int INSTALL_WIDTH = 50;
  private static final int LINE_HEIGHT = 20;

  private final Screen parent;
  private final ModsBrowser browser;
  // Made again at each init: a layout keeps every child added to it.
  private HeaderAndFooterLayout layout;
  private EditBox search;
  private ModsView shown;

  ModsScreen(Screen parent, ModsBrowser browser) {
    super(Component.translatable("rloaded.mods.title"));
    this.parent = parent;
    this.browser = browser;
    this.shown = browser.getView();
  }

  @Override
  protected void init() {
    if (search == null) {
      search =
          new EditBox(
              font, SEARCH_WIDTH, SEARCH_HEIGHT, Component.translatable("rloaded.mods.search"));
      search.setHint(Component.translatable("rloaded.mods.search"));
      search.setValue(shown.getQuery());
      search.setResponder(browser::search);
      browser.open();
    }
    layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, HEADER_HEIGHT / 2);
    LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(SPACING));
    header.defaultCellSetting().alignHorizontallyCenter();
    header.addChild(new StringWidget(getTitle(), font));
    header.addChild(search);

    LinearLayout rows = LinearLayout.vertical().spacing(SPACING);
    switch (shown.getSearch()) {
      case SearchView.Idle idle -> {}
      case SearchView.Searching searching ->
          rows.addChild(text(Component.translatable("rloaded.mods.searching")));
      case SearchView.Failed failed ->
          rows.addChild(text(Component.translatable("rloaded.mods.search_failed")));
      case SearchView.Found found -> {
        if (found.getRows().isEmpty()) {
          rows.addChild(text(Component.translatable("rloaded.mods.no_results")));
        }
        found.getRows().forEach(row -> rows.addChild(line(row)));
      }
    }
    rows.addChild(text(Component.translatable("rloaded.mods.installed_title")));
    if (shown.getInstalled().isEmpty()) {
      rows.addChild(text(Component.translatable("rloaded.mods.installed_empty")));
    }
    shown.getInstalled().forEach(row -> rows.addChild(line(row)));
    layout.addToContents(new ScrollableLayout(minecraft, rows, layout.getContentHeight()));
    layout.addToFooter(
        Button.builder(Component.translatable("gui.done"), button -> onClose()).build());
    layout.visitWidgets(this::addRenderableWidget);
    repositionElements();
    setInitialFocus(search);
  }

  private StringWidget text(Component message) {
    return new StringWidget(width - 2 * MARGIN, LINE_HEIGHT, message, font);
  }

  // What is left for a mod's title once its status and button have their room.
  private int titleWidth() {
    return width - 2 * MARGIN - STATUS_WIDTH - INSTALL_WIDTH - 4 * SPACING;
  }

  private LinearLayout line(ModRow row) {
    LinearLayout line = LinearLayout.horizontal().spacing(SPACING);
    line.addChild(new StringWidget(titleWidth(), LINE_HEIGHT, describe(row), font));
    switch (row.getStatus()) {
      case InstallStatus.Available available -> line.addChild(installButton(row));
      case InstallStatus.Installing installing ->
          line.addChild(status(Component.translatable("rloaded.mods.installing")));
      // Some refusals pass, such as another add running: the player may try again.
      case InstallStatus.Refused refused -> {
        line.addChild(status(Component.translatable(refusal(refused.getProblem()))));
        line.addChild(installButton(row));
      }
      case InstallStatus.Installed installed -> {
        line.addChild(status(Component.translatable("rloaded.mods.installed")));
        if (shown.getRemovals()) {
          line.addChild(button("rloaded.mods.remove", () -> browser.remove(row.getProjectId())));
        }
      }
      case InstallStatus.RemovedAtRestart marked -> {
        line.addChild(status(Component.translatable("rloaded.mods.removed_at_restart")));
        line.addChild(button("rloaded.mods.keep", () -> browser.keep(row.getProjectId())));
      }
      case InstallStatus.Changing changing ->
          line.addChild(status(Component.translatable("rloaded.mods.changing")));
      case InstallStatus.NotChanged notChanged -> {
        line.addChild(status(notChanged(notChanged)));
        line.addChild(button("rloaded.mods.remove", () -> browser.remove(row.getProjectId())));
      }
    }
    return line;
  }

  private Button button(String key, Runnable action) {
    return Button.builder(Component.translatable(key), button -> action.run())
        .width(INSTALL_WIDTH)
        .build();
  }

  private static Component notChanged(InstallStatus.NotChanged notChanged) {
    return switch (notChanged.getProblem()) {
      case NOT_ALLOWED -> Component.translatable("rloaded.mods.not_changed.not_allowed");
      case BUSY -> Component.translatable("rloaded.mods.not_changed.busy");
      case NOT_INSTALLED -> Component.translatable("rloaded.mods.not_changed.not_installed");
      case BASE_SET -> Component.translatable("rloaded.mods.not_changed.base_set");
      case ALREADY_MARKED -> Component.translatable("rloaded.mods.not_changed.already_marked");
      case NOT_MARKED -> Component.translatable("rloaded.mods.not_changed.not_marked");
      case NEEDED ->
          Component.translatable(
              "rloaded.mods.not_changed.needed", String.join(", ", notChanged.getNeededBy()));
      case UNAVAILABLE -> Component.translatable("rloaded.mods.not_changed.unavailable");
      case STORAGE -> Component.translatable("rloaded.mods.not_changed.storage");
    };
  }

  private Button installButton(ModRow row) {
    return Button.builder(
            Component.translatable("rloaded.mods.install"),
            button -> browser.install(row.getProjectId()))
        .width(INSTALL_WIDTH)
        .build();
  }

  // A message too long for its room shows in full when hovered.
  private StringWidget status(Component message) {
    StringWidget status = new StringWidget(STATUS_WIDTH, LINE_HEIGHT, message, font);
    status.setTooltip(Tooltip.create(message));
    return status;
  }

  // Title, then author and downloads when Modrinth said them.
  private static Component describe(ModRow row) {
    MutableComponent text =
        Component.literal(row.getTitle() == null ? row.getProjectId() : row.getTitle());
    if (row.getAuthor() != null) {
      text.append(" ").append(Component.translatable("rloaded.mods.by", row.getAuthor()));
    }
    if (row.getDownloads() != null) {
      text.append(" ")
          .append(
              Component.translatable(
                  "rloaded.mods.downloads",
                  NumberFormat.getIntegerInstance().format(row.getDownloads())));
    }
    return text;
  }

  private static String refusal(AddProblem problem) {
    return switch (problem) {
      case NOT_ALLOWED -> "rloaded.mods.refused.not_allowed";
      case SHARED_WORLD -> "rloaded.mods.refused.shared_world";
      case BUSY -> "rloaded.mods.refused.busy";
      case NOT_FOUND -> "rloaded.mods.refused.not_found";
      case NO_RELEASE -> "rloaded.mods.refused.no_release";
      case ALREADY_INSTALLED -> "rloaded.mods.refused.already_installed";
      case CONFLICT -> "rloaded.mods.refused.conflict";
      case UNSUITABLE -> "rloaded.mods.refused.unsuitable";
      case UNAVAILABLE -> "rloaded.mods.refused.unavailable";
      case UNSUPPORTED -> "rloaded.mods.refused.unsupported";
      case FAILED -> "rloaded.mods.refused.failed";
      case STORAGE -> "rloaded.mods.refused.storage";
    };
  }

  @Override
  protected void repositionElements() {
    layout.arrangeElements();
  }

  // The browser changes from other threads; the screen follows it at each tick.
  @Override
  public void tick() {
    super.tick();
    ModsView now = browser.getView();
    if (!now.equals(shown)) {
      shown = now;
      rebuildWidgets();
    }
  }

  @Override
  public void onClose() {
    minecraft.gui.setScreen(parent);
  }
}
