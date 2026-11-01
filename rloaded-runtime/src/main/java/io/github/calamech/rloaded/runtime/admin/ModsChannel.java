package io.github.calamech.rloaded.runtime.admin;

import io.github.calamech.rloaded.admin.AddActor;
import io.github.calamech.rloaded.admin.AddProblemsKt;
import io.github.calamech.rloaded.admin.AddRefusal;
import io.github.calamech.rloaded.admin.AddedMod;
import io.github.calamech.rloaded.admin.AdmissionService;
import io.github.calamech.rloaded.admin.AuditAction;
import io.github.calamech.rloaded.admin.AuditRequest;
import io.github.calamech.rloaded.admin.LockfileLocation;
import io.github.calamech.rloaded.admin.RemovalRefusal;
import io.github.calamech.rloaded.admin.Removals;
import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.protocol.AddProblem;
import io.github.calamech.rloaded.protocol.AdminEvent;
import io.github.calamech.rloaded.protocol.AdminRequest;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.protocol.RemovalProblem;
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

    /** Whether mods can be removed here, by the players who may add them. */
    boolean removals();

    /** Why no add can start now though the player may add, or empty. */
    Optional<Refusal> blocked();

    /** Who {@code player} is to an add. */
    AddActor actor(ServerPlayer player);

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
            boolean allowed = current.allows(player);
            send(player, new AdminEvent.Access(allowed, allowed && current.removals()));
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
              mods(current).ifPresent(mods -> send(player, mods));
            }
          }
          case AdminRequest.Remove remove ->
              change(current, player, remove.getRequestId(), remove.getProjectId(), true);
          case AdminRequest.Keep keep ->
              change(current, player, keep.getRequestId(), keep.getProjectId(), false);
          default -> throw new IllegalStateException("unknown Mods request");
        }
      }
    }
  }

  private static void add(Target current, ServerPlayer player, AdminRequest.Add add) {
    String name = player.getName().getString();
    Optional<Refusal> blocked = current.blocked();
    AddActor actor = current.actor(player);
    if (!current.allows(player)) {
      LOGGER.warn("{} may not add mods; {} was not added", name, add.getProjectId());
      String detail = name + " may not add mods";
      current
          .service()
          .getAudit()
          .refused(
              actor,
              new AuditRequest(AuditAction.ADD, add.getProjectId(), add.getVersionId()),
              AddProblem.NOT_ALLOWED,
              detail);
      refuse(current, player.getUUID(), add, AddProblem.NOT_ALLOWED, detail);
    } else if (blocked.isPresent()) {
      Refusal refusal = blocked.get();
      current
          .service()
          .getAudit()
          .refused(
              actor,
              new AuditRequest(AuditAction.ADD, add.getProjectId(), add.getVersionId()),
              refusal.problem(),
              refusal.detail());
      refuse(current, player.getUUID(), add, refusal.problem(), refusal.detail());
    } else {
      LOGGER.info("{} asks to add {}", name, add.getProjectId());
      UUID id = player.getUUID();
      CompletableFuture<?> _ =
          current
              .service()
              .addAsync(add.getProjectId(), add.getVersionId(), actor)
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

  // Marks a mod for removal at the next start, or keeps it, for a player who may add mods.
  private static void change(
      Target current, ServerPlayer player, String requestId, String projectId, boolean remove) {
    String name = player.getName().getString();
    AddActor actor = current.actor(player);
    AuditAction action = remove ? AuditAction.REMOVE : AuditAction.KEEP;
    UUID id = player.getUUID();
    if (!current.allows(player) || !current.removals()) {
      String detail = name + " may not remove mods";
      LOGGER.warn("{}; {} was not changed", detail, projectId);
      current
          .service()
          .getAudit()
          .refused(
              actor, new AuditRequest(action, projectId, null), RemovalProblem.NOT_ALLOWED, detail);
      send(
          current,
          id,
          new AdminEvent.RemovalRefused(requestId, RemovalProblem.NOT_ALLOWED, List.of(), detail));
      return;
    }
    Removals removals = current.service().getRemovals();
    CompletableFuture<?> _ =
        (remove ? removals.removeAsync(projectId, actor) : removals.keepAsync(projectId, actor))
            .whenComplete(
                (outcome, error) ->
                    current
                        .server()
                        .execute(() -> changed(current, id, requestId, projectId, outcome, error)));
  }

  private static void changed(
      Target current,
      UUID player,
      String requestId,
      String projectId,
      Outcome<String, RemovalRefusal> outcome,
      Throwable error) {
    switch (outcome) {
      case null -> {
        LOGGER.error("Changing {} failed on an unexpected error", projectId, error);
        send(
            current,
            player,
            new AdminEvent.RemovalRefused(
                requestId, RemovalProblem.STORAGE, List.of(), "unexpected error: " + error));
      }
      case Outcome.Ok<?> ok -> {
        LOGGER.info("{} is now {}", projectId, requestId.startsWith("keep") ? "kept" : "marked");
        send(current, player, new AdminEvent.Changed(requestId));
      }
      case Outcome.Err<?> err -> {
        RemovalRefusal refusal = (RemovalRefusal) err.getError();
        LOGGER.warn("Did not change {}: {}", projectId, refusal.getReason());
        send(
            current,
            player,
            new AdminEvent.RemovalRefused(
                requestId,
                refusal.getProblem(),
                refusal instanceof RemovalRefusal.Needed needed ? needed.getBy() : List.of(),
                refusal.getReason()));
      }
    }
  }

  // The mods the lockfile lists; empty when it cannot be read, which is logged.
  private static Optional<AdminEvent.Mods> mods(Target current) {
    LockfileLocation location = ServerLockfile.of(current.server());
    if (!Files.exists(location.getPath())) {
      return Optional.of(event(location.getWhenMissing()));
    }
    return switch (Lockfile.Companion.read(location.getPath())) {
      case Outcome.Ok<?> ok -> Optional.of(event((Lockfile) ok.getValue()));
      case Outcome.Err<?> err -> {
        LOGGER.error("The lockfile cannot be read: {}", err.getError());
        yield Optional.empty();
      }
    };
  }

  private static AdminEvent.Mods event(Lockfile lockfile) {
    List<String> ids =
        lockfile.getMods().stream().map(mod -> mod.getEntry().getProjectId()).toList();
    return new AdminEvent.Mods(
        ids, lockfile.getPendingRemovals().stream().filter(ids::contains).toList());
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
