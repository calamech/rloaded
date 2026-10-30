package io.github.calamech.rloaded.gametest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.calamech.rloaded.core.lockfile.LockedMod;
import io.github.calamech.rloaded.core.lockfile.Lockfile;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.protocol.Side;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * interface: a plain mod is written to the lockfile with its side, and a mod with biome entries is
 * refused with the reason, leaving the lockfile as it was.
 */
public final class AdminAddGameTest {
  private static final int MAX_TICKS = 400;

  @GameTest(maxTicks = MAX_TICKS)
  public void consoleAddWritesTheLockfileOrSaysWhyNot(GameTestHelper helper) throws Exception {
    MinecraftServer server = helper.getLevel().getServer();
    Path lockfile = server.getServerDirectory().resolve("rloaded").resolve("lockfile.json");
    Files.deleteIfExists(lockfile);
    HttpServer modrinth =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    String url = "http://127.0.0.1:" + modrinth.getAddress().getPort();
    publish(modrinth, url, "admintest", jar("admin-test", Map.of()));
    publish(
        modrinth,
        url,
        "admintestworld",
        jar("admin-test-world", Map.of("data/admin-test-world/worldgen/biome/hot.json", "{}")));
    modrinth.start();
    System.setProperty("rloaded.test.modrinth", url);

    List<String> messages = new CopyOnWriteArrayList<>();
    CommandSourceStack console = server.createCommandSourceStack().withSource(capturing(messages));
    server.getCommands().performPrefixedCommand(console, "rloaded add admintest");
    boolean[] refusedAsked = {false};
    helper.succeedWhen(
        () -> {
          if (!refusedAsked[0] && messages.stream().anyMatch(m -> m.startsWith("Added "))) {
            refusedAsked[0] = true;
            server.getCommands().performPrefixedCommand(console, "rloaded add admintestworld");
          }
          helper.assertTrue(
              messages.stream()
                  .anyMatch(
                      m ->
                          m.startsWith("admintestworld was not added: ")
                              && m.contains("adds data pack entries to worldgen/biome")),
              "no refusal of the biome mod yet: " + messages);
          List<LockedMod> mods = mods(lockfile);
          helper.assertValueEqual(
              mods.stream().map(mod -> mod.getEntry().getProjectId()).toList(),
              List.of("admintest"),
              "projects in the lockfile");
          helper.assertValueEqual(mods.getFirst().getEntry().getSide(), Side.BOTH, "side");
          helper.assertValueEqual(mods.getFirst().getAddedBy(), "console", "added by");
          modrinth.stop(0);
          try {
            Files.deleteIfExists(lockfile);
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private static List<LockedMod> mods(Path lockfile) {
    return switch (Lockfile.Companion.read(lockfile)) {
      case Outcome.Ok<?> ok -> ((Lockfile) ok.getValue()).getMods();
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
  private static void publish(HttpServer modrinth, String url, String project, byte[] jar)
      throws NoSuchAlgorithmException {
    String sha512 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(jar));
    String version =
        "[{\"id\": \"v"
            + project
            + "\", \"project_id\": \""
            + project
            + "\", \"version_number\": \"1.0.0\", \"version_type\": \"release\","
            + " \"date_published\": \"2026-10-01T00:00:00Z\", \"game_versions\": [\"26.3\"],"
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
