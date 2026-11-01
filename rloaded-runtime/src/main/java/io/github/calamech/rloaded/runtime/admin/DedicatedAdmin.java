package io.github.calamech.rloaded.runtime.admin;

import io.github.calamech.rloaded.admin.AddedMod;
import io.github.calamech.rloaded.admin.AdmissionService;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * What the Mods screen may do on a dedicated server: operators of level 4 add mods, as the console
 * does, when {@code [admin] inGame} in {@code rloaded-server.toml} allows it.
 */
final class DedicatedAdmin implements ModsChannel.Target {
  private final MinecraftServer server;
  private final Supplier<AdmissionService> service;
  private final LiveAdd live;
  private final boolean inGame;

  DedicatedAdmin(
      MinecraftServer server, Supplier<AdmissionService> service, LiveAdd live, boolean inGame) {
    this.server = server;
    this.service = service;
    this.live = live;
    this.inGame = inGame;
  }

  @Override
  public MinecraftServer server() {
    return server;
  }

  @Override
  public AdmissionService service() {
    return service.get();
  }

  @Override
  public LiveAdd live() {
    return live;
  }

  @Override
  public boolean allows(ServerPlayer player) {
    return inGame && player.permissions().hasPermission(Permissions.COMMANDS_OWNER);
  }

  @Override
  public Optional<ModsChannel.Refusal> blocked() {
    return Optional.empty();
  }

  @Override
  public String addedBy(ServerPlayer player) {
    return player.getName().getString();
  }

  @Override
  public void added(List<AddedMod> mods) {}

  @Override
  public void restart() {
    ServerRestart.request(server);
  }
}
