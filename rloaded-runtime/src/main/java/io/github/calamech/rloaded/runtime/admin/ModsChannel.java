package io.github.calamech.rloaded.runtime.admin;

import io.github.calamech.rloaded.admin.AddProblemsKt;
import io.github.calamech.rloaded.admin.AddRefusal;
import io.github.calamech.rloaded.admin.AddedMod;
import io.github.calamech.rloaded.admin.AdmissionService;
import io.github.calamech.rloaded.admin.LockfileLocation;
import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.protocol.AddProblem;
import io.github.calamech.rloaded.protocol.AdminEvent;
import io.github.calamech.rloaded.protocol.AdminRequest;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.ServerLockfile;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server's side of the game's Mods screen, for a dedicated server and for the server of a
 * singleplayer world alike: tells each player entering the world whether they may add mods, answers
 * the screen's requests, runs each add through the server's admission service and rollout, and
 * answers once every player has the mods. Who may add is the {@link Target}'s to decide, in one
 * place.
 */
public final class ModsChannel {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");
  private static final int MAX_DETAIL = 16 * 1024;
  // The running server's target; a game runs one server at a time.
  private static volatile Target target;

  private ModsChannel() {}

  /** What a running server lets its players do from the Mods screen. */
  public interface Target {
    /** The server. */
    MinecraftServer server();

    /** Admits and records the mods. */
    AdmissionService service();

    /** Loads them into the running server and rolls them out to players. */
    LiveAdd live();

    /** Whether {@code player} may add mods here. */
    boolean allows(ServerPlayer player);

    /** Why no add can start now though the player may add, or empty. */
    Optional<Refusal> blocked();

    /** Who the lockfile says added the mods. */
    String addedBy(ServerPlayer player);

    /** The mods of an add are recorded; runs on the server thread. */
    void added(List<AddedMod> mods);

    /** Stops the game and asks to be started again, its mods and lockfile disagreeing. */
    void restart();
  }

  /** Why an add does not start, with a detail for logs. */
  public record Refusal(AddProblem problem, String detail) {}

  /** Registers the channel's server side; the game calls it once. */
  public static void register() {
    ServerPlayConnectionEvents.JOIN.register(
        (listener, sender, server) -> {
          Target current = target;
          if (current != null && current.server().equals(server)) {
            ServerPlayer player = listener.getPlayer();
            send(player, new AdminEvent.Access(current.allows(player)));
          }
        });
    ServerPlayNetworking.registerGlobalReceiver(
        AdminPayloads.Request.TYPE,
        (payload, context) -> {
          UUID player = context.player().getUUID();
          context.server().execute(() -> receive(player, payload));
        });
  }

  /** {@code serving} is the target of the server that just started. */
  public static void serve(Target serving) {
    target = serving;
  }

  /** The server stops: its target serves no more. */
  public static void stop(MinecraftServer server) {
    Target current = target;
    if (current != null && current.server().equals(server)) {
      target = null;
    }
  }

  // On the server thread.
  private static void receive(UUID playerId, AdminPayloads.Request payload) {
    Target current = target;
    ServerPlayer player =
        current == null ? null : current.server().getPlayerList().getPlayer(playerId);
    if (player == null) {
      return;
    }
    switch (payload.decode()) {
      case Outcome.Err<?> err ->
          LOGGER.warn("{} sent a Mods request that cannot be read: {}", playerId, err.getError());
      case Outcome.Ok<?> ok -> {
        switch ((AdminRequest) ok.getValue()) {
          case AdminRequest.Add add -> add(current, player, add);
          case AdminRequest.ListMods list -> {
            if (current.allows(player)) {
              mods(current).ifPresent(ids -> send(player, new AdminEvent.Mods(ids)));
            }
          }
          default -> throw new IllegalStateException("unknown Mods request");
        }
      }
    }
  }

  private static void add(Target current, ServerPlayer player, AdminRequest.Add add) {
    String name = player.getName().getString();
    Optional<Refusal> blocked = current.blocked();
    if (!current.allows(player)) {
      LOGGER.warn("{} may not add mods; {} was not added", name, add.getProjectId());
      refuse(current, player.getUUID(), add, AddProblem.NOT_ALLOWED, name + " may not add mods");
    } else if (blocked.isPresent()) {
      refuse(current, player.getUUID(), add, blocked.get().problem(), blocked.get().detail());
    } else {
      LOGGER.info("{} asks to add {}", name, add.getProjectId());
      UUID id = player.getUUID();
      CompletableFuture<?> _ =
          current
              .service()
              .addAsync(add.getProjectId(), add.getVersionId(), current.addedBy(player))
              .whenComplete(
                  (outcome, error) ->
                      current.server().execute(() -> finish(current, id, add, outcome, error)));
    }
  }

  private static void finish(
      Target current,
      UUID player,
      AdminRequest.Add add,
      Outcome<List<AddedMod>, AddRefusal> outcome,
      Throwable error) {
    if (error != null) {
      LOGGER.error("Adding {} failed on an unexpected error", add.getProjectId(), error);
      refuse(current, player, add, AddProblem.FAILED, "unexpected error: " + error);
      return;
    }
    switch (outcome) {
      case Outcome.Ok<?> ok -> {
        List<AddedMod> added =
            ((List<?>) ok.getValue()).stream().map(AddedMod.class::cast).toList();
        current.added(added);
        rollout(current, player, add, added);
      }
      case Outcome.Err<?> err -> {
        AddRefusal refusal = (AddRefusal) err.getError();
        LOGGER.warn("Did not add {}: {}", add.getProjectId(), refusal.getReason());
        refuse(current, player, add, AddProblemsKt.problem(refusal), refusal.getReason());
        // The game runs mods the lockfile does not list: only a restart makes them agree.
        if (refusal instanceof AddRefusal.NotRecorded) {
          current.restart();
        }
      }
    }
  }

  private static void rollout(
      Target current, UUID player, AdminRequest.Add add, List<AddedMod> added) {
    List<String> files = added.stream().map(mod -> mod.getEntry().getFileName()).toList();
    switch (Lockfile.Companion.read(ServerLockfile.of(current.server()).getPath())) {
      case Outcome.Ok<?> ok -> {
        CompletableFuture<?> _ =
            current
                .live()
                .rollout((Lockfile) ok.getValue())
                .thenRun(
                    () -> {
                      LOGGER.info("Added {}: {}", add.getProjectId(), files);
                      send(current, player, new AdminEvent.Added(add.getRequestId(), files));
                    });
      }
      case Outcome.Err<?> err -> {
        LOGGER.error("The lockfile just written cannot be read: {}", err.getError());
        refuse(current, player, add, AddProblem.STORAGE, "the lockfile cannot be read");
        current.restart();
      }
    }
  }

  // The mods the lockfile lists; empty when it cannot be read, which is logged.
  private static Optional<List<String>> mods(Target current) {
    LockfileLocation location = ServerLockfile.of(current.server());
    if (!Files.exists(location.getPath())) {
      return Optional.of(ids(location.getWhenMissing()));
    }
    return switch (Lockfile.Companion.read(location.getPath())) {
      case Outcome.Ok<?> ok -> Optional.of(ids((Lockfile) ok.getValue()));
      case Outcome.Err<?> err -> {
        LOGGER.error("The lockfile cannot be read: {}", err.getError());
        yield Optional.empty();
      }
    };
  }

  private static List<String> ids(Lockfile lockfile) {
    return lockfile.getMods().stream().map(mod -> mod.getEntry().getProjectId()).toList();
  }

  private static void refuse(
      Target current, UUID player, AdminRequest.Add add, AddProblem problem, String detail) {
    String bounded = detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
    send(current, player, new AdminEvent.Refused(add.getRequestId(), problem, bounded));
  }

  // The player object changes when the player goes through the configuration phase: find it again.
  private static void send(Target current, UUID player, AdminEvent event) {
    ServerPlayer found = current.server().getPlayerList().getPlayer(player);
    if (found == null) {
      LOGGER.warn("{} left before getting {}", player, event);
    } else {
      send(found, event);
    }
  }

  private static void send(ServerPlayer player, AdminEvent event) {
    ServerPlayNetworking.send(player, AdminPayloads.Event.of(event));
  }
}
