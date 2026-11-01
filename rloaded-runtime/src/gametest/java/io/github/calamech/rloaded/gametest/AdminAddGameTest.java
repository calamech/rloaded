package io.github.calamech.rloaded.gametest;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.calamech.rloaded.core.lockfile.LockedMod;
import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.protocol.Side;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * Runs {@code rloaded add} the way the server console does, against a fake Modrinth on the loopback
 * interface: a plain mod passes a staging server and is written to the lockfile with its side,
 * while a mod with biome entries, a version published a moment ago, an optimization mod and a mod
 * that throws as it starts on the staging server are refused with the reason, leaving the lockfile
 * as it was.
 */
public final class AdminAddGameTest {
  // The game test server ticks without waiting, so the test paces it at 20 ticks a second while
  // two staging servers start and stop one after the other: 8 minutes at most.
  private static final int MAX_TICKS = 20 * 60 * 8;
  private static final long TICK_MILLIS = 50;
  private static final String STAGING_REFUSED_PROPERTY = "rloaded.staging-refused-mod";
  private static final Instant OLD = Instant.parse("2026-10-01T00:00:00Z");

  @GameTest(maxTicks = MAX_TICKS)
  public void consoleAddWritesTheLockfileOrSaysWhyNot(GameTestHelper helper) throws Exception {
    MinecraftServer server = helper.getLevel().getServer();
    Path lockfile = server.getServerDirectory().resolve("rloaded").resolve("lockfile.json");
    Path audit = lockfile.resolveSibling("audit.log");
    Files.deleteIfExists(lockfile);
    Files.deleteIfExists(audit);
    HttpServer modrinth =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    String url = "http://127.0.0.1:" + modrinth.getAddress().getPort();
    publish(modrinth, url, "admintest", jar("admin-test", Map.of()), OLD);
    publish(
        modrinth,
        url,
        "admintestworld",
        jar("admin-test-world", Map.of("data/admin-test-world/worldgen/biome/hot.json", "{}")),
        OLD);
    publish(modrinth, url, "admintestfresh", jar("admin-test-fresh", Map.of()), Instant.now());
    publish(modrinth, url, "admintestfast", jar("admin-test-fast", Map.of()), OLD);
    String throwsOnInit = System.getProperty(STAGING_REFUSED_PROPERTY);
    helper.assertTrue(
        throwsOnInit != null, "run with the " + STAGING_REFUSED_PROPERTY + " property");
    publish(modrinth, url, "throwsoninit", Files.readAllBytes(Path.of(throwsOnInit)), OLD);
    // Every project has enough downloads and no team members.
    modrinth.createContext(
        "/v2/projects",
        exchange -> {
          String asked = exchange.getRequestURI().getQuery();
          String projects =
              Stream.of(
                      "admintest",
                      "admintestworld",
                      "admintestfresh",
                      "admintestfast",
                      "throwsoninit")
                  .filter(id -> asked.contains("\"" + id + "\""))
                  .map(
                      id ->
                          "{\"id\": \""
                              + id
                              + "\", \"title\": \""
                              + id
                              + "\", \"downloads\": 5000, \"team\": \"t"
                              + id
                              + "\", \"categories\": [\""
                              + (id.equals("admintestfast") ? "optimization" : "utility")
                              + "\"],"
                              + " \"additional_categories\": []}")
                  .collect(Collectors.joining(",", "[", "]"));
          respond(exchange, projects.getBytes(StandardCharsets.UTF_8));
        });
    modrinth.createContext(
        "/v2/teams", exchange -> respond(exchange, "[]".getBytes(StandardCharsets.UTF_8)));
    modrinth.start();
    System.setProperty("rloaded.test.modrinth", url);

    List<String> messages = new CopyOnWriteArrayList<>();
    CommandSourceStack console = server.createCommandSourceStack().withSource(capturing(messages));
    List<String> commands =
        List.of(
            "add admintest",
            "add admintestworld",
            "add admintestfresh",
            "add admintestfast",
            "add throwsoninit",
            "remove admintest",
            "keep admintest",
            "remove admintestnone");
    int[] sent = {0};
    helper.succeedWhen(
        () -> {
          // One command at a time: the next starts once the previous one answered.
          long answered = messages.stream().filter(AdminAddGameTest::isAnswer).count();
          if (answered == sent[0] && sent[0] < commands.size()) {
            server
                .getCommands()
                .performPrefixedCommand(console, "rloaded " + commands.get(sent[0]));
            sent[0]++;
          }
          if (answered < commands.size()) {
            pace();
          }
          helper.assertTrue(answered == commands.size(), "commands still running: " + messages);
          helper.assertTrue(
              messages.containsAll(
                  List.of(
                      "admintest is removed at the next start of the server, after the world is"
                          + " saved.",
                      "admintest stays.",
                      "admintestnone was not removed: admintestnone is not installed")),
              "the removal commands did not answer as expected: " + messages);
          helper.assertTrue(
              messages.stream()
                  .anyMatch(
                      m ->
                          m.startsWith("admintestworld was not added: ")
                              && m.contains("adds data pack entries to worldgen/biome")),
              "the biome mod was not refused by the classifier: " + messages);
          helper.assertTrue(
              messages.stream()
                  .anyMatch(
                      m ->
                          m.startsWith("admintestfresh was not added: ")
                              && m.contains("less than 72 hours ago")),
              "the new version was not refused as too recent: " + messages);
          helper.assertTrue(
              messages.stream()
                  .anyMatch(
                      m ->
                          m.startsWith("admintestfast was not added: ")
                              && m.contains("is in the optimization category")),
              "the optimization mod was not refused by the compatibility database: " + messages);
          helper.assertTrue(
              messages.stream()
                  .anyMatch(
                      m ->
                          m.startsWith(
                                  "throwsoninit was not added: it failed on the staging server: ")
                              && m.contains(
                                  "throws-on-init failed as it started: java.lang.IllegalStateException:"
                                      + " throws-on-init fails as it starts, on purpose")
                              && m.contains("latest.log")),
              "the mod that throws as it starts was not refused by the staging server: "
                  + messages);
          List<LockedMod> mods = mods(lockfile);
          helper.assertValueEqual(
              read(lockfile).getPendingRemovals(), List.of(), "mods still to remove after keep");
          helper.assertValueEqual(
              mods.stream().map(mod -> mod.getEntry().getProjectId()).toList(),
              List.of("admintest"),
              "projects in the lockfile");
          helper.assertValueEqual(mods.getFirst().getEntry().getSide(), Side.BOTH, "side");
          helper.assertValueEqual(mods.getFirst().getAddedBy(), "console", "added by");
          helper.assertValueEqual(
              auditTrail(audit),
              List.of(
                  "requested add admintest by console",
                  "added [admintest]",
                  "requested add admintestworld by console",
                  "refused UNSUPPORTED",
                  "requested add admintestfresh by console",
                  "refused UNSUITABLE",
                  "requested add admintestfast by console",
                  "refused UNSUITABLE",
                  "requested add throwsoninit by console",
                  "refused UNSUPPORTED",
                  "requested remove admintest by console",
                  "marked",
                  "requested keep admintest by console",
                  "kept",
                  "requested remove admintestnone by console",
                  "refused NOT_INSTALLED"),
              "audit log");
          modrinth.stop(0);
          try {
            Files.deleteIfExists(lockfile);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  // Each line of the audit log, in short: what happened, and to what or why.
  private static List<String> auditTrail(Path audit) {
    try {
      return Files.readAllLines(audit).stream()
          .map(line -> JsonParser.parseString(line).getAsJsonObject())
          .map(
              line ->
                  switch (line.get("event").getAsString()) {
                    case "requested" ->
                        "requested "
                            + line.get("action").getAsString()
                            + " "
                            + line.get("project").getAsString()
                            + " by "
                            + line.get("actor").getAsString();
                    case "added" ->
                        "added "
                            + line.getAsJsonArray("mods").asList().stream()
                                .map(mod -> mod.getAsJsonObject().get("project").getAsString())
                                .toList();
                    case "refused" -> "refused " + line.get("problem").getAsString();
                    case "marked", "kept" -> line.get("event").getAsString();
                    default -> "unknown " + line;
                  })
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean isAnswer(String message) {
    return message.startsWith("Added ")
        || message.contains(" was not added: ")
        || message.contains(" is removed at the next start")
        || message.endsWith(" stays.")
        || message.contains(" was not removed: ")
        || message.contains(" was not kept: ");
  }

  private static void pace() {
    try {
      Thread.sleep(TICK_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the adds", e);
    }
  }

  private static List<LockedMod> mods(Path lockfile) {
    return read(lockfile).getMods();
  }

  private static Lockfile read(Path lockfile) {
    return switch (Lockfile.Companion.read(lockfile)) {
      case Outcome.Ok<?> ok -> (Lockfile) ok.getValue();
      case Outcome.Err<?> err -> throw new AssertionError("lockfile: " + err.getError());
    };
  }

  private static CommandSource capturing(List<String> messages) {
    return new CommandSource() {
      @Override
      public void sendSystemMessage(Component message) {
        messages.add(message.getString());
      }

      @Override
      public boolean acceptsSuccess() {
        return true;
      }

      @Override
      public boolean acceptsFailure() {
        return true;
      }

      @Override
      public boolean shouldInformAdmins() {
        return false;
      }
    };
  }

  // One release of the project for Fabric 26.3, the way Modrinth lists it.
  private static void publish(
      HttpServer modrinth, String url, String project, byte[] jar, Instant published)
      throws NoSuchAlgorithmException {
    String sha512 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(jar));
    String version =
        "[{\"id\": \"v"
            + project
            + "\", \"project_id\": \""
            + project
            + "\", \"version_number\": \"1.0.0\", \"version_type\": \"release\","
            + " \"date_published\": \""
            + published
            + "\", \"game_versions\": [\"26.3\"],"
            + " \"loaders\": [\"fabric\"], \"dependencies\": [], \"files\": [{\"url\": \""
            + url
            + "/cdn/"
            + project
            + ".jar\", \"filename\": \""
            + project
            + ".jar\", \"size\": "
            + jar.length
            + ", \"primary\": true, \"hashes\": {\"sha512\": \""
            + sha512
            + "\"}}]}]";
    modrinth.createContext(
        "/v2/project/" + project + "/version",
        exchange -> respond(exchange, version.getBytes(StandardCharsets.UTF_8)));
    modrinth.createContext("/cdn/" + project + ".jar", exchange -> respond(exchange, jar));
  }

  private static void respond(HttpExchange exchange, byte[] body) throws IOException {
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(body);
    }
  }

  private static byte[] jar(String id, Map<String, String> files) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("fabric.mod.json"));
      zip.write(
          ("{\"schemaVersion\": 1, \"id\": \"" + id + "\", \"version\": \"1.0.0\"}")
              .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      for (Map.Entry<String, String> file : files.entrySet()) {
        zip.putNextEntry(new ZipEntry(file.getKey()));
        zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }
}
