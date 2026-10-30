package io.github.calamech.rloaded.runtime.join;

import io.github.calamech.rloaded.core.join.JoinRefusal;
import io.github.calamech.rloaded.core.join.ServerJoin;
import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.core.trust.ServerIdentity;
import io.github.calamech.rloaded.protocol.InstallResult;
import io.github.calamech.rloaded.protocol.Outcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

/**
 * The server's side of a join on a dedicated server: every joining player goes through the {@code
 * rloaded:install} configuration task, which sends the server's hello and its signed manifest and
 * waits for the client's result. It runs before Fabric's registry sync, so the client has loaded
 * the mods' content when raw ids are synced.
 */
public final class JoinServer {
  private static final Identifier BEFORE_REGISTRY_SYNC =
      Identifier.fromNamespaceAndPath("rloaded", "install");
  private static final int MAX_SERVER_NAME = 64;
  private static volatile ServerJoin join;

  private JoinServer() {}

  /** Registers the join on the server side. */
  public static void register() {
    ServerLifecycleEvents.SERVER_STARTING.register(JoinServer::start);
    ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.addPhaseOrdering(
        BEFORE_REGISTRY_SYNC, Event.DEFAULT_PHASE);
    ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register(
        BEFORE_REGISTRY_SYNC, (listener, server) -> configure(listener));
    ServerConfigurationNetworking.registerGlobalReceiver(
        JoinPayloads.Hello.TYPE,
        (payload, context) -> {
          if (join.clientHello(payload.bytes()) instanceof Outcome.Err<?> refused) {
            context.packetListener().disconnect(refusal((JoinRefusal) refused.getError()));
          }
        });
    ServerConfigurationNetworking.registerGlobalReceiver(
        JoinPayloads.Progress.TYPE, (payload, context) -> {});
    ServerConfigurationNetworking.registerGlobalReceiver(
        JoinPayloads.Result.TYPE,
        (payload, context) -> {
          switch (join.result(payload.bytes())) {
            case Outcome.Ok<?> ok ->
                finish(context.packetListener(), (InstallResult) ok.getValue());
            case Outcome.Err<?> err ->
                context.packetListener().disconnect(refusal((JoinRefusal) err.getError()));
          }
        });
  }

  // A missing identity is created; a broken identity or lockfile stops the server before anyone
  // joins.
  private static void start(MinecraftServer server) {
    Path directory = server.getServerDirectory().resolve("rloaded");
    ServerIdentity identity =
        switch (ServerIdentity.Companion.loadOrCreate(directory.resolve("identity.key"))) {
          case Outcome.Ok<?> ok -> (ServerIdentity) ok.getValue();
          case Outcome.Err<?> err ->
              throw new IllegalStateException("cannot use the server identity: " + err.getError());
        };
    Path lockfilePath = directory.resolve("lockfile.json");
    Lockfile lockfile =
        !Files.exists(lockfilePath)
            ? new Lockfile(
                SharedConstants.getCurrentVersion().name(),
                rloadedVersion(),
                List.of(),
                List.of(),
                List.of())
            : switch (Lockfile.Companion.read(lockfilePath)) {
              case Outcome.Ok<?> ok -> (Lockfile) ok.getValue();
              case Outcome.Err<?> err ->
                  throw new IllegalStateException(
                      "cannot use " + lockfilePath + ": " + err.getError());
            };
    join = new ServerJoin(identity, lockfile, serverName(server));
  }

  private static void configure(ServerConfigurationPacketListenerImpl listener) {
    if (!ServerConfigurationNetworking.canSend(listener, JoinPayloads.Hello.TYPE)) {
      listener.disconnect(
          Component.translatableWithFallback(
              "rloaded.disconnect.no_launcher",
              "This server installs its mods for you: join it from the Rloaded launcher."));
      return;
    }
    listener.addTask(new InstallTask());
  }

  private static void finish(ServerConfigurationPacketListenerImpl listener, InstallResult result) {
    switch (result) {
      case InstallResult.Loaded loaded -> listener.completeTask(InstallTask.TYPE);
      case InstallResult.RefusedByUser refused ->
          listener.disconnect(
              Component.translatableWithFallback(
                  "rloaded.disconnect.declined", "You did not accept this server's mods."));
      case InstallResult.Failed failed ->
          listener.disconnect(
              Component.translatableWithFallback(
                  "rloaded.disconnect.failed",
                  "The mods could not be installed: %s",
                  failed.getReport()));
      default -> throw new IllegalStateException("unknown install result " + result);
    }
  }

  private static Component refusal(JoinRefusal refusal) {
    return switch (refusal) {
      case JoinRefusal.OtherProtocol other ->
          Component.translatableWithFallback(
              "rloaded.disconnect.other_protocol",
              "Update your Rloaded launcher to join this server.");
      case JoinRefusal.Malformed malformed ->
          Component.translatableWithFallback(
              "rloaded.disconnect.malformed", "Your game sent a message the server cannot read.");
      default -> throw new IllegalStateException("unknown refusal " + refusal);
    };
  }

  private static String serverName(MinecraftServer server) {
    Path folder = server.getServerDirectory().toAbsolutePath().normalize().getFileName();
    String name = folder == null ? "Minecraft server" : folder.toString();
    return name.length() > MAX_SERVER_NAME ? name.substring(0, MAX_SERVER_NAME) : name;
  }

  private static String rloadedVersion() {
    return FabricLoader.getInstance()
        .getModContainer("rloaded")
        .orElseThrow()
        .getMetadata()
        .getVersion()
        .getFriendlyString();
  }

  /** Sends the hello and the manifest, then waits for the client's result. */
  private static final class InstallTask implements ConfigurationTask {
    static final Type TYPE = new Type("rloaded:install");

    @Override
    public void start(Consumer<Packet<?>> sender) {
      sender.accept(
          ServerConfigurationNetworking.createClientboundPacket(
              new JoinPayloads.Hello(join.hello())));
      sender.accept(
          ServerConfigurationNetworking.createClientboundPacket(
              new JoinPayloads.Manifest(join.manifest())));
    }

    @Override
    public Type type() {
      return TYPE;
    }
  }
}
