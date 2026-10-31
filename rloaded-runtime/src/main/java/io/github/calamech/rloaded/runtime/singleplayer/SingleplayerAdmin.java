package io.github.calamech.rloaded.runtime.singleplayer;

import io.github.calamech.rloaded.admin.AddProblemsKt;
import io.github.calamech.rloaded.admin.AddRefusal;
import io.github.calamech.rloaded.admin.AddTarget;
import io.github.calamech.rloaded.admin.AddedMod;
import io.github.calamech.rloaded.admin.AdmissionService;
import io.github.calamech.rloaded.admin.CompatDatabase;
import io.github.calamech.rloaded.admin.LiveGame;
import io.github.calamech.rloaded.admin.LockfileLocation;
import io.github.calamech.rloaded.admin.ReputationRules;
import io.github.calamech.rloaded.admin.staging.Staging;
import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.protocol.AddProblem;
import io.github.calamech.rloaded.protocol.AdminEvent;
import io.github.calamech.rloaded.protocol.AdminRequest;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.ModrinthAccess;
import io.github.calamech.rloaded.runtime.ServerLockfile;
import io.github.calamech.rloaded.runtime.admin.AdminPayloads;
import io.github.calamech.rloaded.runtime.admin.Admissions;
import io.github.calamech.rloaded.runtime.admin.LiveAdd;
import io.github.calamech.rloaded.runtime.join.client.GameRelaunch;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds the mods the owner of a singleplayer world asks for from the game's Mods screen. The world's
 * integrated server admits them as a dedicated server does, without a staging server, and records
 * them in the world's own lockfile. Only the world's owner may add, and not while the world is open
 * to other players on the local network; each player learns whether they may when they enter the
 * world.
 */
public final class SingleplayerAdmin {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");
  private static final String MOD_STORE_PROPERTY = "rloaded.mod-store";
  private static final String ADDED_BY = "owner";
  private static final int MAX_DETAIL = 16 * 1024;
  // The open world's add pipeline: set when its server started, cleared when it stops.
  private static volatile World world;

  private record World(
      IntegratedServer server, IntegratedServerHost host, LiveAdd live, AdmissionService service) {}

  private SingleplayerAdmin() {}

  /** Serves the Mods screen of singleplayer worlds; the client calls it once. */
  public static void register() {
    String modStore = System.getProperty(MOD_STORE_PROPERTY);
    if (modStore == null) {
      LOGGER.info("Started without the launcher: mods cannot be added to singleplayer worlds");
      return;
    }
    ModrinthAccess access = ModrinthAccess.open();
    CompatDatabase compat = Admissions.bundledCompatDatabase();
    IntegratedServerHost.register();
    ServerLifecycleEvents.SERVER_STARTED.register(
        server -> {
          if (server instanceof IntegratedServer integrated) {
            world = open(integrated, access, Path.of(modStore), compat);
          }
        });
    ServerLifecycleEvents.SERVER_STOPPING.register(
        server -> {
          World open = world;
          if (open != null && open.server().equals(server)) {
            world = null;
            open.host().worldClosing();
          }
        });
    ServerPlayConnectionEvents.JOIN.register(
        (listener, sender, server) -> {
          World open = world;
          if (open != null && open.server().equals(server)) {
            send(listener.getPlayer(), new AdminEvent.Access(isOwner(open, listener.getPlayer())));
          }
        });
    ServerPlayNetworking.registerGlobalReceiver(
        AdminPayloads.Request.TYPE,
        (payload, context) -> {
          UUID player = context.player().getUUID();
          context.server().execute(() -> receive(player, payload));
        });
  }

  private static World open(
      IntegratedServer server, ModrinthAccess access, Path modStore, CompatDatabase compat) {
    IntegratedServerHost host = new IntegratedServerHost(Minecraft.getInstance());
    LiveAdd live = new LiveAdd(server, host);
    ReputationRules rules =
        new ReputationRules(
            Duration.ofHours(ReputationRules.DEFAULT_VERSION_AGE_HOURS),
            ReputationRules.DEFAULT_PROJECT_DOWNLOADS);
    AdmissionService service =
        Admissions.service(
            access,
            modStore,
            rules,
            () -> compat,
            Staging.Companion.getOFF(),
            new AddTarget(ServerLockfile.of(server), live, LiveGame.SINGLEPLAYER));
    return new World(server, host, live, service);
  }

  private static boolean isOwner(World open, ServerPlayer player) {
    return open.server().isSingleplayerOwner(player.nameAndId());
  }

  // On the server thread.
  private static void receive(UUID playerId, AdminPayloads.Request payload) {
    World open = world;
    ServerPlayer player = open == null ? null : open.server().getPlayerList().getPlayer(playerId);
    if (player == null) {
      return;
    }
    switch (payload.decode()) {
      case Outcome.Err<?> err ->
          LOGGER.warn("{} sent a Mods request that cannot be read: {}", playerId, err.getError());
      case Outcome.Ok<?> ok -> {
        switch ((AdminRequest) ok.getValue()) {
          case AdminRequest.Add add -> add(open, player, add);
          case AdminRequest.ListMods list -> {
            if (isOwner(open, player)) {
              mods(open).ifPresent(ids -> send(player, new AdminEvent.Mods(ids)));
            }
          }
          default -> throw new IllegalStateException("unknown Mods request");
        }
      }
    }
  }

  private static void add(World open, ServerPlayer player, AdminRequest.Add add) {
    if (!isOwner(open, player)) {
      refuse(player.getUUID(), add, AddProblem.NOT_ALLOWED, "only the world's owner adds mods");
    } else if (open.server().isPublished()) {
      refuse(
          player.getUUID(), add, AddProblem.SHARED_WORLD, "the world is open to the local network");
    } else {
      LOGGER.info(
          "{} asks to add {} to the open world", player.getName().getString(), add.getProjectId());
      UUID id = player.getUUID();
      CompletableFuture<?> _ =
          open.service()
              .addAsync(add.getProjectId(), add.getVersionId(), ADDED_BY)
              .whenComplete(
                  (outcome, error) ->
                      open.server().execute(() -> finish(open, id, add, outcome, error)));
    }
  }

  private static void finish(
      World open,
      UUID player,
      AdminRequest.Add add,
      Outcome<List<AddedMod>, AddRefusal> outcome,
      Throwable error) {
    if (error != null) {
      LOGGER.error("Adding {} failed on an unexpected error", add.getProjectId(), error);
      refuse(player, add, AddProblem.FAILED, "unexpected error: " + error);
      return;
    }
    switch (outcome) {
      case Outcome.Ok<?> ok -> rollout(open, player, add, files((List<?>) ok.getValue()));
      case Outcome.Err<?> err -> {
        AddRefusal refusal = (AddRefusal) err.getError();
        LOGGER.warn("Did not add {}: {}", add.getProjectId(), refusal.getReason());
        refuse(player, add, AddProblemsKt.problem(refusal), refusal.getReason());
        // The game runs mods the world's lockfile does not list: only a restart makes them agree.
        if (refusal instanceof AddRefusal.NotRecorded) {
          GameRelaunch.request(Minecraft.getInstance());
        }
      }
    }
  }

  private static void rollout(World open, UUID player, AdminRequest.Add add, List<String> files) {
    switch (Lockfile.Companion.read(ServerLockfile.of(open.server()).getPath())) {
      case Outcome.Ok<?> ok -> {
        CompletableFuture<?> _ =
            open.live()
                .rollout((Lockfile) ok.getValue())
                .thenRun(
                    () -> {
                      LOGGER.info("Added {}: {}", add.getProjectId(), files);
                      send(open, player, new AdminEvent.Added(add.getRequestId(), files));
                    });
      }
      case Outcome.Err<?> err -> {
        LOGGER.error("The lockfile just written cannot be read: {}", err.getError());
        refuse(player, add, AddProblem.STORAGE, "the world's lockfile cannot be read");
        GameRelaunch.request(Minecraft.getInstance());
      }
    }
  }

  // The mods the world's lockfile lists; empty when it cannot be read, which is logged.
  private static Optional<List<String>> mods(World open) {
    LockfileLocation location = ServerLockfile.of(open.server());
    if (!Files.exists(location.getPath())) {
      return Optional.of(ids(location.getWhenMissing()));
    }
    return switch (Lockfile.Companion.read(location.getPath())) {
      case Outcome.Ok<?> ok -> Optional.of(ids((Lockfile) ok.getValue()));
      case Outcome.Err<?> err -> {
        LOGGER.error("The world's lockfile cannot be read: {}", err.getError());
        yield Optional.empty();
      }
    };
  }

  private static List<String> ids(Lockfile lockfile) {
    return lockfile.getMods().stream().map(mod -> mod.getEntry().getProjectId()).toList();
  }

  private static List<String> files(List<?> added) {
    return added.stream().map(mod -> ((AddedMod) mod).getEntry().getFileName()).toList();
  }

  private static void refuse(UUID player, AdminRequest.Add add, AddProblem problem, String detail) {
    String bounded = detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
    World open = world;
    if (open != null) {
      send(open, player, new AdminEvent.Refused(add.getRequestId(), problem, bounded));
    }
  }

  // The player object changes when the player goes through the configuration phase: find it again.
  private static void send(World open, UUID player, AdminEvent event) {
    ServerPlayer current = open.server().getPlayerList().getPlayer(player);
    if (current == null) {
      LOGGER.warn("{} left before getting {}", player, event);
    } else {
      send(current, event);
    }
  }

  private static void send(ServerPlayer player, AdminEvent event) {
    ServerPlayNetworking.send(player, AdminPayloads.Event.of(event));
  }
}
