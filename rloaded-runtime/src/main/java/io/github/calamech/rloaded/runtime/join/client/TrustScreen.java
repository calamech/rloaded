package io.github.calamech.rloaded.runtime.join.client;

import io.github.calamech.rloaded.modrinth.ModrinthProject;
import io.github.calamech.rloaded.protocol.ModEntry;
import io.github.calamech.rloaded.protocol.Side;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.ServerReconfigScreen;
import net.minecraft.network.chat.Component;

/**
 * Asks the player whether to trust a server and install its mods. Client-only mods have a box, ticked
 * by default, that leaves them out when cleared; mods both sides need cannot be left out.
 */
final class TrustScreen extends Screen {
  private static final int HEADER_HEIGHT = 64;
  private static final int SPACING = 4;
  private static final int SHOWN_AUTHORS = 2;
  private static final double THOUSAND = 1_000;
  private static final double MILLION = 1_000_000;

  private final Screen parent;
  private final List<ModEntry> mods;
  private final Map<String, ModrinthProject> projects;
  private final Consumer<Set<String>> onInstall;
  private final Runnable onLeave;
  private final Set<String> optedOut = new LinkedHashSet<>();
  private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, HEADER_HEIGHT / 2);

  TrustScreen(
      Screen parent,
      String serverName,
      List<ModEntry> mods,
      Map<String, ModrinthProject> projects,
      Consumer<Set<String>> onInstall,
      Runnable onLeave) {
    super(title(serverName, mods.size()));
    this.parent = parent;
    this.mods = mods;
    this.projects = projects;
    this.onInstall = onInstall;
    this.onLeave = onLeave;
  }

  private static Component title(String serverName, int count) {
    return switch (count) {
      case 0 -> Component.translatable("rloaded.trust.title.none", serverName);
      case 1 -> Component.translatable("rloaded.trust.title.one", serverName);
      default -> Component.translatable("rloaded.trust.title.many", serverName, count);
    };
  }

  @Override
  protected void init() {
    LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(SPACING));
    header.defaultCellSetting().alignHorizontallyCenter();
    header.addChild(new StringWidget(getTitle(), font));
    header.addChild(new StringWidget(Component.translatable(mods.isEmpty() ? "rloaded.trust.subtitle.none" : "rloaded.trust.subtitle"), font));

    LinearLayout rows = LinearLayout.vertical().spacing(SPACING);
    for (ModEntry mod : mods) {
      rows.addChild(row(mod));
    }
    rows.addChild(new StringWidget(Component.translatable("rloaded.trust.future"), font));
    layout.addToContents(new ScrollableLayout(minecraft, rows, layout.getContentHeight()));

    LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(SPACING * 2));
    footer.addChild(Button.builder(Component.translatable("rloaded.trust.install"), button -> onInstall.accept(Set.copyOf(optedOut))).build());
    footer.addChild(Button.builder(Component.translatable("rloaded.trust.leave"), button -> onLeave.run()).build());
    layout.visitWidgets(this::addRenderableWidget);
    repositionElements();
  }

  private LinearLayout row(ModEntry mod) {
    ModrinthProject project = projects.get(mod.getProjectId());
    Component name = Component.literal(project == null ? mod.getProjectId() : project.getTitle());
    LinearLayout row = LinearLayout.horizontal().spacing(SPACING * 2);
    if (mod.getSide() == Side.CLIENT) {
      row.addChild(
          Checkbox.builder(name.copy().append(" ").append(Component.translatable("rloaded.trust.client")), font)
              .selected(true)
              .onValueChange(
                  (box, selected) -> {
                    if (selected) {
                      optedOut.remove(mod.getProjectId());
                    } else {
                      optedOut.add(mod.getProjectId());
                    }
                  })
              .build());
    } else {
      row.addChild(new StringWidget(name, font));
    }
    if (project != null) {
      row.addChild(new StringWidget(authors(project.getAuthors()), font));
      row.addChild(new StringWidget(Component.translatable("rloaded.trust.downloads", downloads(project.getDownloads())), font));
    }
    return row;
  }

  private static Component authors(List<String> authors) {
    if (authors.isEmpty()) {
      return Component.empty();
    }
    String shown = String.join(", ", authors.subList(0, Math.min(SHOWN_AUTHORS, authors.size())));
    int more = authors.size() - SHOWN_AUTHORS;
    return more > 0 ? Component.translatable("rloaded.trust.by_more", shown, more) : Component.translatable("rloaded.trust.by", shown);
  }

  private static String downloads(long count) {
    if (count >= MILLION) {
      return String.format(Locale.ROOT, "%.1fM", count / MILLION);
    }
    if (count >= THOUSAND) {
      return String.format(Locale.ROOT, "%.1fK", count / THOUSAND);
    }
    return Long.toString(count);
  }

  @Override
  protected void repositionElements() {
    layout.arrangeElements();
  }

  @Override
  public boolean shouldCloseOnEsc() {
    return false;
  }

  // The connection is ticked by the screen it was opened from; keep ticking it while the player reads.
  @Override
  public void tick() {
    super.tick();
    if (parent instanceof ConnectScreen || parent instanceof ServerReconfigScreen) {
      parent.tick();
    }
  }
}
