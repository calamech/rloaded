package io.github.calamech.rloaded.runtime.admin;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.calamech.rloaded.admin.AddRefusal;
import io.github.calamech.rloaded.admin.AddedMod;
import io.github.calamech.rloaded.admin.AdmissionService;
import io.github.calamech.rloaded.admin.CompatDatabase;
import io.github.calamech.rloaded.admin.JarRules;
import io.github.calamech.rloaded.admin.RecordChecks;
import io.github.calamech.rloaded.admin.ReputationRules;
import io.github.calamech.rloaded.admin.Resolver;
import io.github.calamech.rloaded.core.download.ArtifactStore;
import io.github.calamech.rloaded.core.download.RetryPolicy;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.ModrinthAccess;
import io.github.calamech.rloaded.runtime.ServerLockfile;
import io.github.calamech.rloaded.runtime.engine.Admission;
import io.github.calamech.rloaded.runtime.engine.HotAdd;
import io.github.calamech.rloaded.runtime.engine.HotAddRefusal;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The console commands an admin manages the server's mods with. {@code rloaded add <project>
 * [version]} admits a mod from Modrinth and writes it, with its dependencies, to the lockfile: the
 * server loads it at its next start, and players install it when they next join.
 */
public final class AdminCommands {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");
  private static final String PROJECT = "project";
  private static final String VERSION = "version";
  private static volatile ReputationRules rules;
  private static volatile CompatDatabase compat;
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
          compat = bundledCompatDatabase();
        });
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
            .addAsync(project, version, "console")
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
        LOGGER.info("Added {} to the lockfile: {}", project, files);
        source.sendSuccess(
            () ->
                Component.translatableWithFallback(
                    "rloaded.admin.added",
                    "Added %s. Restart the server to load them; players get them when they next"
                        + " join.",
                    files),
            false);
      }
      case Outcome.Err<?> err -> {
        String reason = ((AddRefusal) err.getError()).getReason();
        LOGGER.warn("Did not add {}: {}", project, reason);
        source.sendFailure(refused(project, reason));
      }
    }
  }

  private static Component refused(String project, String reason) {
    return Component.translatableWithFallback(
        "rloaded.admin.refused", "%s was not added: %s", project, reason);
  }

  private static synchronized AdmissionService service(MinecraftServer server) {
    if (service == null) {
      ModrinthAccess access = ModrinthAccess.open();
      Path cache = server.getServerDirectory().resolve("rloaded").resolve("cache").resolve("mods");
      Clock clock = Clock.systemUTC();
      service =
          new AdmissionService(
              new Resolver(access.modrinth(), SharedConstants.getCurrentVersion().name()),
              new RecordChecks(access.modrinth(), rules, compat, clock),
              new ArtifactStore(
                  cache, access.http(), new RetryPolicy(), ArtifactStore.DEFAULT_MAX_UNSIZED_BYTES),
              new JarRules(Admission.classifier(), AdminCommands::serverRefusal),
              ServerLockfile.of(server),
              clock);
    }
    return service;
  }

  // The database ships in the mod jar; a broken one is a defect of the build.
  private static CompatDatabase bundledCompatDatabase() {
    try (InputStream input = AdminCommands.class.getResourceAsStream("compat.json")) {
      if (input == null) {
        throw new IllegalStateException("compat.json is missing from the runtime mod");
      }
      return switch (CompatDatabase.Companion.parse(
          new String(input.readAllBytes(), StandardCharsets.UTF_8))) {
        case Outcome.Ok<?> ok -> (CompatDatabase) ok.getValue();
        case Outcome.Err<?> err ->
            throw new IllegalStateException("compat.json cannot be read: " + err.getError());
      };
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // The rules the server applies to the mods it loads itself, without loading them.
  private static String serverRefusal(List<? extends Path> jars) {
    try {
      HotAdd.check(List.copyOf(jars));
      return null;
    } catch (HotAddRefusal refusal) {
      return refusal.getMessage();
    }
  }
}
