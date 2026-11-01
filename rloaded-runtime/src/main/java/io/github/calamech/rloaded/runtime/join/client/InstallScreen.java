package io.github.calamech.rloaded.runtime.join.client;

import io.github.calamech.rloaded.core.join.InstallProgress;
import io.github.calamech.rloaded.protocol.ModEntry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.ServerReconfigScreen;
import net.minecraft.network.chat.Component;

/**
 * Shows a joining player where the install of the server's mods is: the step, how much is
 * downloaded, a bar, and each mod with its state. "Leave" gives up the join.
 */
final class InstallScreen extends Screen {
  private static final int HEADER_HEIGHT = 72;
  private static final int SPACING = 4;
  private static final int BAR_WIDTH = 200;
  private static final int BAR_HEIGHT = 4;
  private static final int BAR_MARGIN = 10;
  private static final int BAR_BACKGROUND = 0xFF404040;
  private static final int BAR_FILL = 0xFF55FF55;
  private static final double KIBIBYTE = 1024;
  private static final double MEBIBYTE = KIBIBYTE * KIBIBYTE;

  private final Screen parent;
  private final List<ModEntry> mods;
  private final Map<String, String> titles;
  private final InstallProgress progress;
  private final Runnable onLeave;
  // Made again at each init: a layout keeps every child added to it.
  private HeaderAndFooterLayout layout;
  private InstallProgress.Snapshot shown;

  InstallScreen(
      Screen parent,
      String serverName,
      List<ModEntry> mods,
      Map<String, String> titles,
      InstallProgress progress,
      Runnable onLeave) {
    super(Component.translatable("rloaded.install.title", serverName));
    this.parent = parent;
    this.mods = mods;
    this.titles = titles;
    this.progress = progress;
    this.onLeave = onLeave;
    this.shown = progress.snapshot();
  }

  @Override
  protected void init() {
    layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, HEADER_HEIGHT / 2);
    LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(SPACING));
    header.defaultCellSetting().alignHorizontallyCenter();
    header.addChild(new StringWidget(getTitle(), font));
    header.addChild(new StringWidget(step(shown), font));

    LinearLayout rows = LinearLayout.vertical().spacing(SPACING);
    for (int i = 0; i < mods.size(); i++) {
      ModEntry mod = mods.get(i);
      LinearLayout row = LinearLayout.horizontal().spacing(SPACING * 2);
      row.addChild(
          new StringWidget(
              Component.literal(titles.getOrDefault(mod.getProjectId(), mod.getFileName())), font));
      row.addChild(new StringWidget(state(shown.getStates().get(i)), font));
      rows.addChild(row);
    }
    layout.addToContents(new ScrollableLayout(minecraft, rows, layout.getContentHeight()));
    layout.addToFooter(
        Button.builder(Component.translatable("rloaded.install.leave"), button -> onLeave.run())
            .build());
    layout.visitWidgets(this::addRenderableWidget);
    repositionElements();
  }

  private static Component step(InstallProgress.Snapshot snapshot) {
    return switch (snapshot.getStage()) {
      case LOAD -> Component.translatable("rloaded.install.loading");
      case RELOAD -> Component.translatable("rloaded.install.reloading");
      case TRUST, DOWNLOAD, VERIFY -> downloading(snapshot);
    };
  }

  // Sizes in megabytes, or in kilobytes when the whole download is smaller than a megabyte.
  private static Component downloading(InstallProgress.Snapshot snapshot) {
    boolean small = snapshot.getTotalBytes() < MEBIBYTE;
    double unit = small ? KIBIBYTE : MEBIBYTE;
    return Component.translatable(
        small ? "rloaded.install.downloading.kb" : "rloaded.install.downloading.mb",
        snapshot.getDownloadedMods(),
        snapshot.getTotalMods(),
        String.format(Locale.ROOT, "%.1f", snapshot.getDownloadedBytes() / unit),
        String.format(Locale.ROOT, "%.1f", snapshot.getTotalBytes() / unit));
  }

  private static Component state(InstallProgress.ModState state) {
    return switch (state) {
      case WAITING -> Component.translatable("rloaded.install.waiting");
      case DOWNLOADED -> Component.translatable("rloaded.install.downloaded");
      case LOADED -> Component.translatable("rloaded.install.loaded");
    };
  }

  @Override
  protected void repositionElements() {
    layout.arrangeElements();
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
    super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    int left = (width - BAR_WIDTH) / 2;
    int top = HEADER_HEIGHT - BAR_MARGIN;
    graphics.fill(left, top, left + BAR_WIDTH, top + BAR_HEIGHT, BAR_BACKGROUND);
    graphics.fill(
        left,
        top,
        left + (int) Math.round(BAR_WIDTH * shown.getFraction()),
        top + BAR_HEIGHT,
        BAR_FILL);
  }

  @Override
  public boolean shouldCloseOnEsc() {
    return false;
  }

  // The connection is ticked by the screen it was opened from; keep ticking it during the install.
  @Override
  public void tick() {
    super.tick();
    InstallProgress.Snapshot now = progress.snapshot();
    if (!now.equals(shown)) {
      shown = now;
      rebuildWidgets();
    }
    if (parent instanceof ConnectScreen || parent instanceof ServerReconfigScreen) {
      parent.tick();
    }
  }
}
