package io.github.calamech.rloaded.runtime.singleplayer;

import io.github.calamech.rloaded.admin.AddProblemsKt;
import io.github.calamech.rloaded.admin.AddRefusal;
import io.github.calamech.rloaded.admin.AddTarget;
import io.github.calamech.rloaded.admin.AddedMod;
import io.github.calamech.rloaded.admin.AdmissionService;
import io.github.calamech.rloaded.admin.CompatDatabase;
import io.github.calamech.rloaded.admin.LiveGame;
import io.github.calamech.rloaded.admin.ReputationRules;
import io.github.calamech.rloaded.admin.staging.Staging;
import io.github.calamech.rloaded.core.link.LauncherLink;
import io.github.calamech.rloaded.core.link.LinkError;
import io.github.calamech.rloaded.core.link.LinkSettings;
import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.protocol.AddProblem;
import io.github.calamech.rloaded.protocol.LinkEvent;
import io.github.calamech.rloaded.protocol.LinkRequest;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.ModrinthAccess;
import io.github.calamech.rloaded.runtime.ServerLockfile;
import io.github.calamech.rloaded.runtime.admin.Admissions;
import io.github.calamech.rloaded.runtime.admin.LiveAdd;
import io.github.calamech.rloaded.runtime.join.client.GameRelaunch;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adds the mods the launcher asks for to the world the game has open, through the link the game
 * opens to its launcher at start. The world's integrated server admits them as a dedicated server
 * does, without a staging server, and records them in the world's own lockfile. The launcher is the
 * only one that can ask: it started the game for the player who owns the world, and a world open to
 * other players on the local network is refused.
 */
public final class LauncherRequests {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");
  private static final String MOD_STORE_PROPERTY = "rloaded.mod-store";
  private static final String ADDED_BY = "launcher";
  private static final int MAX_DETAIL = 16 * 1024;
  // The open world's add pipeline: set when its server started, cleared when it stops.
  private static volatile World world;

  private record World(
      IntegratedServer server, IntegratedServerHost host, LiveAdd live, AdmissionService service) {}

  private LauncherRequests() {}

  /** Connects to the launcher, if it started the game, and serves its requests. */
  public static void register() {
    String modStore = System.getProperty(MOD_STORE_PROPERTY);
    switch (LauncherLink.Companion.connect(System::getenv, LinkSettings.Companion.getDEFAULT())) {
      case Outcome.Ok<?> ok when ok.getValue() == null ->
          LOGGER.info("Started without a launcher: mods cannot be added to singleplayer worlds");
      case Outcome.Ok<?> ok when modStore == null -> {
        LOGGER.error("The launcher did not say where mods are kept: no mod can be added");
        ((LauncherLink) ok.getValue()).close();
      }
      case Outcome.Ok<?> ok -> serve((LauncherLink) ok.getValue(), Path.of(modStore));
      case Outcome.Err<?> err ->
          LOGGER.error("The launcher cannot be reached: {}", (LinkError) err.getError());
    }
  }

  private static void serve(LauncherLink link, Path modStore) {
    ModrinthAccess access = ModrinthAccess.open();
    CompatDatabase compat = Admissions.bundledCompatDatabase();
    ServerLifecycleEvents.SERVER_STARTED.register(
        server -> {
          if (server instanceof IntegratedServer integrated) {
            world = open(integrated, access, modStore, compat);
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
    IntegratedServerHost.register();
    Thread.ofPlatform().daemon().name("Rloaded launcher link").start(() -> receive(link));
    LOGGER.info("Connected to the launcher");
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

  private static void receive(LauncherLink link) {
    while (true) {
      switch (link.receive()) {
        case Outcome.Ok<?> ok -> handle(link, (LinkRequest) ok.getValue());
        case Outcome.Err<?> err -> {
          switch ((LinkError) err.getError()) {
            case LinkError.Closed closed ->
                LOGGER.info("The launcher closed the link: {}", closed.getReason());
            case LinkError error ->
                LOGGER.error("The launcher sent what cannot be read: {}", error);
          }
          link.close();
          return;
        }
      }
    }
  }

  private static void handle(LauncherLink link, LinkRequest request) {
    if (!(request instanceof LinkRequest.Add add)) {
      throw new IllegalStateException("unknown request " + request);
    }
    Minecraft client = Minecraft.getInstance();
    client.execute(
        () -> {
          World open = world;
          if (open == null || !open.server().equals(client.getSingleplayerServer())) {
            refuse(link, add, AddProblem.NO_WORLD, "no singleplayer world is open");
          } else if (open.server().isPublished()) {
            refuse(link, add, AddProblem.SHARED_WORLD, "the world is open to the local network");
          } else {
            LOGGER.info("The launcher asks to add {} to the open world", add.getProjectId());
            CompletableFuture<?> _ =
                open.service()
                    .addAsync(add.getProjectId(), add.getVersionId(), ADDED_BY)
                    .whenComplete(
                        (outcome, error) ->
                            open.server().execute(() -> finish(link, open, add, outcome, error)));
          }
        });
  }

  private static void finish(
      LauncherLink link,
      World open,
      LinkRequest.Add add,
      Outcome<List<AddedMod>, AddRefusal> outcome,
      Throwable error) {
    if (error != null) {
      LOGGER.error("Adding {} failed on an unexpected error", add.getProjectId(), error);
      refuse(link, add, AddProblem.FAILED, "unexpected error: " + error);
      return;
    }
    switch (outcome) {
      case Outcome.Ok<?> ok -> rollout(link, open, add, files((List<?>) ok.getValue()));
      case Outcome.Err<?> err -> {
        AddRefusal refusal = (AddRefusal) err.getError();
        LOGGER.warn("Did not add {}: {}", add.getProjectId(), refusal.getReason());
        refuse(link, add, AddProblemsKt.problem(refusal), refusal.getReason());
        // The game runs mods the world's lockfile does not list: only a restart makes them agree.
        if (refusal instanceof AddRefusal.NotRecorded) {
          GameRelaunch.request(Minecraft.getInstance());
        }
      }
    }
  }

  private static void rollout(
      LauncherLink link, World open, LinkRequest.Add add, List<String> files) {
    MinecraftServer server = open.server();
    switch (Lockfile.Companion.read(ServerLockfile.of(server).getPath())) {
      case Outcome.Ok<?> ok -> {
        CompletableFuture<?> _ =
            open.live()
                .rollout((Lockfile) ok.getValue())
                .thenRun(
                    () -> {
                      LOGGER.info("Added {}: {}", add.getProjectId(), files);
                      send(link, new LinkEvent.Added(add.getRequestId(), files));
                    });
      }
      case Outcome.Err<?> err -> {
        LOGGER.error("The lockfile just written cannot be read: {}", err.getError());
        refuse(link, add, AddProblem.STORAGE, "the world's lockfile cannot be read");
        GameRelaunch.request(Minecraft.getInstance());
      }
    }
  }

  private static List<String> files(List<?> added) {
    return added.stream().map(mod -> ((AddedMod) mod).getEntry().getFileName()).toList();
  }

  private static void refuse(
      LauncherLink link, LinkRequest.Add add, AddProblem problem, String detail) {
    String bounded = detail.length() > MAX_DETAIL ? detail.substring(0, MAX_DETAIL) : detail;
    send(link, new LinkEvent.Refused(add.getRequestId(), problem, bounded));
  }

  private static void send(LauncherLink link, LinkEvent event) {
    if (link.send(event) instanceof Outcome.Err<?> err) {
      LOGGER.warn("The launcher did not get {}: {}", event, err.getError());
    }
  }
}
