package io.github.calamech.rloaded.runtime.admin;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.calamech.rloaded.admin.AddActor;
import io.github.calamech.rloaded.admin.AddRefusal;
import io.github.calamech.rloaded.admin.AddTarget;
import io.github.calamech.rloaded.admin.AddedMod;
import io.github.calamech.rloaded.admin.AdmissionService;
import io.github.calamech.rloaded.admin.InGameAdmin;
import io.github.calamech.rloaded.admin.LiveGame;
import io.github.calamech.rloaded.admin.LocalCompat;
import io.github.calamech.rloaded.admin.ReputationRules;
import io.github.calamech.rloaded.admin.staging.Staging;
import io.github.calamech.rloaded.admin.staging.StagingServer;
import io.github.calamech.rloaded.admin.staging.StagingSettings;
import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.core.runtime.ServerLaunch;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.ModrinthAccess;
import io.github.calamech.rloaded.runtime.ServerLockfile;
import io.github.calamech.rloaded.runtime.join.JoinServer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The console commands an admin manages the server's mods with. {@code rloaded add <project>
 * [version]} admits a mod from Modrinth, tries it on a staging server, loads it into the running
 * server, writes it with its dependencies to the lockfile, and has every connected player install
 * it without leaving. Operators add the same way from the game's Mods screen ({@link
 * DedicatedAdmin}).
 */
public final class AdminCommands {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");
  private static final String PROJECT = "project";
  private static final String VERSION = "version";
  private static volatile ReputationRules rules;
  private static volatile LocalCompat compat;
  private static volatile Staging staging;
  private static volatile LiveAdd live;
  private static AdmissionService service;

  private AdminCommands() {}

  /** Registers the commands; the server calls it once. */
  public static void register() {
    // The rules come from rloaded-server.toml through the bootstrap; a server without them does not
    // start.
    ServerLifecycleEvents.SERVER_STARTING.register(
        server -> {
          rules =
              switch (ReputationRules.Companion.fromProperties(System::getProperty)) {
                case Outcome.Ok<?> ok -> (ReputationRules) ok.getValue();
                case Outcome.Err<?> err ->
                    throw new IllegalStateException(err.getError().toString());
              };
          compat = localCompat(server);
          JoinServer.recordFailuresIn(compat);
          staging = staging(server);
          live = new LiveAdd(server, new DedicatedServerHost(server));
          InGameAdmin inGame =
              switch (InGameAdmin.Companion.fromProperties(System::getProperty)) {
                case Outcome.Ok<?> ok -> (InGameAdmin) ok.getValue();
                case Outcome.Err<?> err ->
                    throw new IllegalStateException(err.getError().toString());
              };
          if (!inGame.getEnabled()) {
            LOGGER.info("Adding mods from the game is off in rloaded-server.toml");
          }
          ModsChannel.serve(new DedicatedAdmin(server, () -> service(server), live, inGame));
        });
    ServerLifecycleEvents.SERVER_STOPPING.register(ModsChannel::stop);
    ServerRestart.register();
    CommandRegistrationCallback.EVENT.register(
        (dispatcher, context, selection) ->
            dispatcher.register(
                Commands.literal("rloaded")
                    .requires(AdminCommands::isConsole)
                    .then(
                        Commands.literal("add")
                            .then(
                                Commands.argument(PROJECT, StringArgumentType.word())
                                    .executes(command -> add(command, null))
                                    .then(
                                        Commands.argument(VERSION, StringArgumentType.word())
                                            .executes(
                                                command ->
                                                    add(
                                                        command,
                                                        StringArgumentType.getString(
                                                            command, VERSION))))))));
  }

  // The server console or RCON: not a player, whatever their level, and not a command block.
  private static boolean isConsole(CommandSourceStack source) {
    return source.getEntity() == null
        && source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
  }

  private static int add(CommandContext<CommandSourceStack> command, String version) {
    CommandSourceStack source = command.getSource();
    MinecraftServer server = source.getServer();
    String project = StringArgumentType.getString(command, PROJECT);
    source.sendSuccess(
        () -> Component.translatableWithFallback("rloaded.admin.adding", "Adding %s…", project),
        false);
    CompletableFuture<?> _ =
        service(server)
            .addAsync(project, version, AddActor.Console.INSTANCE)
            .whenComplete(
                (outcome, error) -> server.execute(() -> report(source, project, outcome, error)));
    return 1;
  }

  private static void report(
      CommandSourceStack source,
      String project,
      Outcome<List<AddedMod>, AddRefusal> outcome,
      Throwable error) {
    if (error != null) {
      LOGGER.error("Adding {} failed on an unexpected error", project, error);
      source.sendFailure(refused(project, "unexpected error: " + error));
      return;
    }
    switch (outcome) {
      case Outcome.Ok<?> ok -> {
        String files =
            ((List<?>) ok.getValue())
                .stream()
                    .map(added -> ((AddedMod) added).getEntry().getFileName())
                    .collect(Collectors.joining(", "));
        LOGGER.info("Added {}: {}", project, files);
        source.sendSuccess(
            () ->
                Component.translatableWithFallback(
                    "rloaded.admin.added", "Added %s. Players install them now.", files),
            false);
        rollout(source.getServer());
      }
      case Outcome.Err<?> err -> {
        AddRefusal refusal = (AddRefusal) err.getError();
        LOGGER.warn("Did not add {}: {}", project, refusal.getReason());
        source.sendFailure(refused(project, refusal.getReason()));
        // The server runs mods its lockfile does not list: only a restart makes them agree.
        if (refusal instanceof AddRefusal.NotRecorded) {
          ServerRestart.request(source.getServer());
        }
      }
    }
  }

  private static void rollout(MinecraftServer server) {
    switch (Lockfile.Companion.read(ServerLockfile.of(server).getPath())) {
      case Outcome.Ok<?> ok -> {
        CompletableFuture<?> _ = live.rollout((Lockfile) ok.getValue());
      }
      case Outcome.Err<?> err -> {
        LOGGER.error("The lockfile just written cannot be read: {}", err.getError());
        ServerRestart.request(server);
      }
    }
  }

  private static Component refused(String project, String reason) {
    return Component.translatableWithFallback(
        "rloaded.admin.refused", "%s was not added: %s", project, reason);
  }

  private static synchronized AdmissionService service(MinecraftServer server) {
    if (service == null) {
      Path cache = server.getServerDirectory().resolve("rloaded").resolve("cache").resolve("mods");
      service =
          Admissions.service(
              ModrinthAccess.open(),
              cache,
              rules,
              () -> compat.current(),
              staging,
              new AddTarget(
                  ServerLockfile.of(server),
                  live,
                  LiveGame.DEDICATED_SERVER,
                  Admissions.auditLog(ServerLockfile.of(server))));
    }
    return service;
  }

  // A server that stages needs the launch the bootstrap describes; without it, it does not start.
  private static Staging staging(MinecraftServer server) {
    StagingSettings settings =
        switch (StagingSettings.Companion.fromProperties(System::getProperty)) {
          case Outcome.Ok<?> ok -> (StagingSettings) ok.getValue();
          case Outcome.Err<?> err -> throw new IllegalStateException(err.getError().toString());
        };
    if (!settings.getEnabled()) {
      LOGGER.warn(
          "Staging is off in rloaded-server.toml: mods are added without being tried first");
      return Staging.Companion.getOFF();
    }
    String described = System.getProperty(ServerLaunch.PROPERTY);
    if (described == null) {
      throw new IllegalStateException(
          ServerLaunch.PROPERTY + " is not set; start the server with rloaded-server.jar");
    }
    ServerLaunch launch =
        switch (ServerLaunch.Companion.read(Path.of(described))) {
          case Outcome.Ok<?> ok -> (ServerLaunch) ok.getValue();
          case Outcome.Err<?> err -> throw new IllegalStateException(err.getError().toString());
        };
    Path directory = server.getServerDirectory();
    return new StagingServer(
        launch,
        directory.resolve("rloaded").resolve("staging"),
        directory.resolve("eula.txt"),
        settings.getTimeout());
  }

  // What this server recorded goes on top of the shipped database; a file it cannot read stops the
  // server, so the admin fixes or removes it.
  private static LocalCompat localCompat(MinecraftServer server) {
    Path file = server.getServerDirectory().resolve("rloaded").resolve("compat-local.json");
    return switch (LocalCompat.Companion.open(file, Admissions.bundledCompatDatabase())) {
      case Outcome.Ok<?> ok -> (LocalCompat) ok.getValue();
      case Outcome.Err<?> err -> throw new IllegalStateException(err.getError().toString());
    };
  }
}
