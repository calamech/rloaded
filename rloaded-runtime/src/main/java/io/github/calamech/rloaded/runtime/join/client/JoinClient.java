package io.github.calamech.rloaded.runtime.join.client;

import io.github.calamech.rloaded.core.download.ArtifactStore;
import io.github.calamech.rloaded.core.download.KtorHttp;
import io.github.calamech.rloaded.core.download.RetryPolicy;
import io.github.calamech.rloaded.core.join.ClientJoin;
import io.github.calamech.rloaded.core.join.InstallProgress;
import io.github.calamech.rloaded.core.join.JoinFailure;
import io.github.calamech.rloaded.core.join.ManifestStep;
import io.github.calamech.rloaded.core.trust.TrustStore;
import io.github.calamech.rloaded.modrinth.InstallError;
import io.github.calamech.rloaded.modrinth.InstalledMod;
import io.github.calamech.rloaded.modrinth.ModInstaller;
import io.github.calamech.rloaded.modrinth.Modrinth;
import io.github.calamech.rloaded.modrinth.ModrinthError;
import io.github.calamech.rloaded.modrinth.ModrinthProject;
import io.github.calamech.rloaded.protocol.InstallResult;
import io.github.calamech.rloaded.protocol.InstallStage;
import io.github.calamech.rloaded.protocol.ModEntry;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.protocol.Side;
import io.github.calamech.rloaded.runtime.AgentBridge;
import io.github.calamech.rloaded.runtime.ModrinthAccess;
import io.github.calamech.rloaded.runtime.engine.HotAdd;
import io.github.calamech.rloaded.runtime.engine.HotAddRefusal;
import io.github.calamech.rloaded.runtime.engine.LateKeyMappings;
import io.github.calamech.rloaded.runtime.join.JoinPayloads;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's side of a join: answers the server's hello, checks its manifest, asks the player to
 * trust the server the first time, then downloads the mods from Modrinth, loads them with {@link
 * HotAdd} before the world exists, reloads resources and tells the server. Every failure is sent to
 * the server as a failed install, which ends the join with the reason.
 */
public final class JoinClient {
  private static final Logger LOGGER = LoggerFactory.getLogger("Rloaded");
  private static final String MOD_STORE_PROPERTY = "rloaded.mod-store";
  private static final String TRUST_STORE_PROPERTY = "rloaded.trust-store";
  private static final int MAX_REPORT = 1000;
  private static final int PERCENT = 100;

  /** The toast that names the mods an add brought while the player was in the world. */
  public static final SystemToast.SystemToastId ADDED_TOAST = new SystemToast.SystemToastId();

  /** Project ids this game loaded during the session, with their version ids. */
  private static final Map<String, String> LOADED = new ConcurrentHashMap<>();

  private static ClientJoin current;
  // Whether the player was in the world on this connection before: the configuration is then an
  // add.
  private static volatile boolean inSession;
  // Set when the player leaves or the server ends the join: what is still in flight is dropped.
  private static volatile boolean ended;
  private static Connection connection;

  private JoinClient() {}

  /** What the client needs from its launcher to install mods. */
  private record Connection(
      Path trustStore, KtorHttp http, Modrinth modrinth, ModInstaller installer) {}

  /** Registers the join on the client side. */
  public static void register() {
    ClientConfigurationConnectionEvents.DISCONNECT.register((listener, client) -> ended = true);
    ClientLoginConnectionEvents.INIT.register((listener, client) -> inSession = false);
    ClientConfigurationConnectionEvents.COMPLETE.register((listener, client) -> inSession = true);
    ClientConfigurationNetworking.registerGlobalReceiver(
        JoinPayloads.Hello.TYPE,
        (payload, context) ->
            context.client().execute(() -> onHello(payload, context.responseSender())));
    ClientConfigurationNetworking.registerGlobalReceiver(
        JoinPayloads.Manifest.TYPE,
        (payload, context) ->
            context
                .client()
                .execute(() -> onManifest(payload, context.client(), context.responseSender())));
  }

  private static void onHello(JoinPayloads.Hello payload, PacketSender sender) {
    Connection setup = connection();
    TrustStore store = setup == null ? null : open(setup.trustStore());
    if (store == null) {
      // The manifest that follows is answered with the reason.
      current = null;
      return;
    }
    ended = false;
    current = new ClientJoin(store, Map.copyOf(LOADED), rloadedVersion());
    switch (current.serverHello(payload.bytes())) {
      case Outcome.Ok<?> ok -> sender.sendPacket(new JoinPayloads.Hello((byte[]) ok.getValue()));
      case Outcome.Err<?> err ->
          fail(sender, null, "the server's hello cannot be read: " + err.getError());
    }
  }

  private static void onManifest(
      JoinPayloads.Manifest payload, Minecraft client, PacketSender sender) {
    Connection setup = connection();
    if (setup == null || current == null) {
      fail(sender, null, "start the game from the Rloaded launcher to join this server");
      return;
    }
    switch (current.manifest(payload.bytes())) {
      case Outcome.Err<?> err -> fail(sender, null, describe((JoinFailure) err.getError()));
      case Outcome.Ok<?> ok -> {
        switch ((ManifestStep) ok.getValue()) {
          case ManifestStep.Install install ->
              install(setup, client, sender, install.getEntries(), Map.of());
          case ManifestStep.AskTrust ask -> askTrust(setup, client, sender, ask);
          default -> throw new IllegalStateException("unknown manifest step");
        }
      }
    }
  }

  private static void askTrust(
      Connection setup, Minecraft client, PacketSender sender, ManifestStep.AskTrust ask) {
    List<ModEntry> shown =
        ask.getEntries().stream().filter(entry -> entry.getSide() != Side.SERVER).toList();
    List<String> ids = shown.stream().map(ModEntry::getProjectId).toList();
    onClientThread(
        setup.modrinth().projectsAsync(ids),
        client,
        sender,
        projects -> showTrust(setup, client, sender, ask, shown, projects));
  }

  private static void showTrust(
      Connection setup,
      Minecraft client,
      PacketSender sender,
      ManifestStep.AskTrust ask,
      List<ModEntry> shown,
      Outcome<Map<String, ModrinthProject>, ModrinthError> projects) {
    if (projects instanceof Outcome.Err<?> err) {
      fail(sender, null, "Modrinth cannot be reached: " + err.getError());
      return;
    }
    @SuppressWarnings("unchecked")
    Map<String, ModrinthProject> details =
        (Map<String, ModrinthProject>) ((Outcome.Ok<?>) projects).getValue();
    Screen previous = client.gui.screen();
    client.gui.setScreen(
        new TrustScreen(
            previous,
            ask.getServerName(),
            shown,
            details,
            optedOut -> {
              client.gui.setScreen(previous);
              switch (current.trust(ask.getEntries(), optedOut)) {
                case Outcome.Ok<?> ok ->
                    install(setup, client, sender, entries(ok.getValue()), titles(details));
                case Outcome.Err<?> err ->
                    fail(sender, null, describe((JoinFailure) err.getError()));
              }
            },
            () -> leave(client, sender, previous),
            () -> progress(sender, InstallStage.TRUST, 0)));
  }

  private static Map<String, String> titles(Map<String, ModrinthProject> projects) {
    return projects.values().stream()
        .collect(Collectors.toUnmodifiableMap(ModrinthProject::getId, ModrinthProject::getTitle));
  }

  private static void leave(Minecraft client, PacketSender sender, Screen previous) {
    ended = true;
    client.gui.setScreen(previous);
    sender.sendPacket(
        new JoinPayloads.Result(ClientJoin.Companion.result(InstallResult.RefusedByUser.INSTANCE)));
  }

  private static void install(
      Connection setup,
      Minecraft client,
      PacketSender sender,
      List<ModEntry> entries,
      Map<String, String> titles) {
    if (entries.isEmpty()) {
      sender.sendPacket(
          new JoinPayloads.Result(ClientJoin.Companion.result(InstallResult.Loaded.INSTANCE)));
      return;
    }
    Screen previous = client.gui.screen();
    InstallProgress shown = new InstallProgress(entries);
    String serverName = current.getServerName();
    client.gui.setScreen(
        new InstallScreen(
            previous,
            serverName == null ? "" : serverName,
            entries,
            titles,
            shown,
            () -> leave(client, sender, previous)));
    onClientThread(
        setup
            .installer()
            .installAsync(
                entries,
                (entry, done, total) -> {
                  shown.downloaded(entry);
                  progress(sender, InstallStage.DOWNLOAD, done * PERCENT / total);
                }),
        client,
        sender,
        outcome -> load(client, sender, outcome, shown, previous));
  }

  private static void load(
      Minecraft client,
      PacketSender sender,
      Outcome<List<InstalledMod>, InstallError> outcome,
      InstallProgress shown,
      Screen previous) {
    if (outcome instanceof Outcome.Err<?> err) {
      fail(sender, null, "the mods cannot be downloaded: " + err.getError());
      return;
    }
    @SuppressWarnings("unchecked")
    List<InstalledMod> mods = (List<InstalledMod>) ((Outcome.Ok<?>) outcome).getValue();
    shown.stage(InstallStage.LOAD);
    progress(sender, InstallStage.LOAD, 0);
    LateKeyMappings.open();
    try {
      HotAdd.load(
          mods.stream().map(InstalledMod::getJar).toList(),
          AgentBridge.instrumentation().orElseThrow());
    } catch (HotAddRefusal refusal) {
      String failing =
          mods.stream()
              .filter(mod -> mod.getJar().equals(refusal.jar()))
              .map(mod -> mod.getEntry().getProjectId())
              .findFirst()
              .orElse(null);
      fail(sender, failing, refusal.getMessage());
      return;
    } finally {
      LateKeyMappings.close(client);
    }
    mods.forEach(mod -> LOADED.put(mod.getEntry().getProjectId(), mod.getEntry().getVersionId()));
    shown.stage(InstallStage.RELOAD);
    progress(sender, InstallStage.RELOAD, 0);
    onClientThread(
        client.reloadResourcePacks(),
        client,
        sender,
        reloaded -> {
          client.gui.setScreen(previous);
          sender.sendPacket(
              new JoinPayloads.Result(ClientJoin.Companion.result(InstallResult.Loaded.INSTANCE)));
          if (inSession) {
            announce(client, mods);
          }
        });
  }

  // Players accepted the server's future mods when they trusted it: a toast tells them what came.
  private static void announce(Minecraft client, List<InstalledMod> mods) {
    List<String> ids = mods.stream().map(mod -> mod.getEntry().getProjectId()).toList();
    CompletableFuture<?> _ =
        connection()
            .modrinth()
            .projectsAsync(ids)
            .whenComplete(
                (projects, error) ->
                    client.execute(() -> showAddedToast(client, ids, projects, error)));
  }

  private static void showAddedToast(
      Minecraft client,
      List<String> ids,
      Outcome<Map<String, ModrinthProject>, ModrinthError> projects,
      Throwable error) {
    @SuppressWarnings("unchecked")
    Map<String, ModrinthProject> details =
        error == null && projects instanceof Outcome.Ok<?> ok
            ? (Map<String, ModrinthProject>) ok.getValue()
            : Map.of();
    String names =
        ids.stream()
            .map(id -> details.containsKey(id) ? details.get(id).getTitle() : id)
            .collect(Collectors.joining(", "));
    SystemToast.add(
        client.gui.toastManager(),
        ADDED_TOAST,
        Component.translatableWithFallback("rloaded.toast.added", "New mods on this server"),
        Component.literal(names));
  }

  /**
   * Runs {@code action} on the client thread once {@code future} completes. The futures here
   * complete with an {@link Outcome}, so an exception is a defect: it ends the join rather than
   * leaving the server waiting for a result that never comes.
   */
  private static <T> void onClientThread(
      CompletableFuture<T> future, Minecraft client, PacketSender sender, Consumer<T> action) {
    CompletableFuture<?> _ =
        future.whenComplete(
            (value, error) ->
                client.execute(
                    () -> {
                      if (ended) {
                        LOGGER.info("The join ended; the rest of the install is dropped");
                      } else if (error == null) {
                        action.accept(value);
                      } else {
                        LOGGER.error("Joining failed on an unexpected error", error);
                        sendFailure(sender, null, "unexpected error: " + error);
                      }
                    }));
  }

  private static void progress(PacketSender sender, InstallStage stage, int percent) {
    sender.sendPacket(new JoinPayloads.Progress(ClientJoin.Companion.progress(stage, percent)));
  }

  private static void fail(PacketSender sender, String failingMod, String report) {
    LOGGER.error("Joining failed: {}", report);
    sendFailure(sender, failingMod, report);
  }

  private static void sendFailure(PacketSender sender, String failingMod, String report) {
    String bounded = report.length() > MAX_REPORT ? report.substring(0, MAX_REPORT) : report;
    sender.sendPacket(
        new JoinPayloads.Result(
            ClientJoin.Companion.result(new InstallResult.Failed(failingMod, bounded))));
  }

  private static String describe(JoinFailure failure) {
    return switch (failure) {
      case JoinFailure.Malformed malformed ->
          "the server sent a message that cannot be read: " + malformed.getReason();
      case JoinFailure.NoHello noHello -> "the server sent its mods before saying who it is";
      case JoinFailure.BadSignature bad -> "the list of mods is not signed by the server's key";
      case JoinFailure.Conflicts conflicts ->
          "restart the game to change the version of "
              + conflicts.getConflicts().stream()
                  .map(conflict -> conflict.getProjectId())
                  .collect(Collectors.joining(", "));
      case JoinFailure.Trust trust -> "your decision cannot be saved: " + trust.getError();
      default -> throw new IllegalStateException("unknown join failure " + failure);
    };
  }

  @SuppressWarnings("unchecked")
  private static List<ModEntry> entries(Object value) {
    return (List<ModEntry>) value;
  }

  private static TrustStore open(Path file) {
    return switch (TrustStore.Companion.open(file)) {
      case Outcome.Ok<?> ok -> (TrustStore) ok.getValue();
      case Outcome.Err<?> err -> {
        LOGGER.error("The trust store cannot be read: {}", err.getError());
        yield null;
      }
    };
  }

  // The launcher passes where to keep mods and trust; without them the client cannot install
  // anything.
  private static synchronized Connection connection() {
    if (connection != null) {
      return connection;
    }
    String modStore = System.getProperty(MOD_STORE_PROPERTY);
    String trustStore = System.getProperty(TRUST_STORE_PROPERTY);
    if (modStore == null || trustStore == null) {
      return null;
    }
    ModrinthAccess access = ModrinthAccess.open();
    KtorHttp http = access.http();
    Modrinth modrinth = access.modrinth();
    ArtifactStore store =
        new ArtifactStore(
            Path.of(modStore), http, new RetryPolicy(), ArtifactStore.DEFAULT_MAX_UNSIZED_BYTES);
    connection =
        new Connection(
            Path.of(trustStore),
            http,
            modrinth,
            new ModInstaller(modrinth, store, ModInstaller.DEFAULT_PARALLELISM));
    return connection;
  }

  private static String rloadedVersion() {
    return FabricLoader.getInstance()
        .getModContainer("rloaded")
        .orElseThrow()
        .getMetadata()
        .getVersion()
        .getFriendlyString();
  }
}
