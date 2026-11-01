package io.github.calamech.rloaded.gametest;

import io.github.calamech.rloaded.runtime.engine.HotAdd;
import io.github.calamech.rloaded.runtime.engine.HotAddRefusal;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Checks that mods {@link HotAdd} cannot load are refused with a reason before anything of them is
 * registered. Each jar holds only a {@code fabric.mod.json}: refusals happen before any class
 * loads.
 */
public final class HotAddRefusalGameTest {
  @GameTest
  public void unsupportedModsAreRefusedBeforeAnythingRuns(GameTestHelper helper) throws Exception {
    Path directory = Files.createTempDirectory("rloaded-refused");
    List<String> mismatches = new ArrayList<>();
    expect(
        mismatches,
        directory,
        "already.jar",
        mod("fabric-api", ""),
        "already.jar is refused: a mod with id fabric-api is already loaded");
    expect(
        mismatches,
        directory,
        "client.jar",
        mod("only-client", "\"environment\": \"client\","),
        "client.jar is refused: it does not load on the server");
    expect(
        mismatches,
        directory,
        "nested.jar",
        mod("nested", "\"jars\": [{\"file\": \"META-INF/jars/lib.jar\"}],"),
        "nested.jar is refused: it lists the nested jar META-INF/jars/lib.jar, which it does not"
            + " contain");
    expect(
        mismatches,
        directory,
        "widener.jar",
        mod("widener", "\"accessWidener\": \"widener.accesswidener\","),
        "widener.jar is refused: it widens access to game classes, which cannot be done in a running game yet");
    expect(
        mismatches,
        directory,
        "adapter.jar",
        mod("adapter", "\"languageAdapters\": {\"custom\": \"example.Adapter\"},"),
        "adapter.jar is refused: mod adapter needs the language adapters custom, which only work from"
            + " startup");
    expect(
        mismatches,
        directory,
        "foreign.jar",
        mod(
            "foreign",
            "\"entrypoints\": {\"main\": [{\"adapter\": \"scala\", \"value\": \"a.Main\"}]},"),
        "foreign.jar is refused: mod foreign needs the language adapters scala, which only work from"
            + " startup");
    expect(
        mismatches,
        directory,
        "prelaunch.jar",
        mod("prelaunch", "\"entrypoints\": {\"preLaunch\": [\"example.Early\"]},"),
        "prelaunch.jar is refused: mod prelaunch has a preLaunch entrypoint, which runs before the game"
            + " starts");
    expect(
        mismatches,
        directory,
        "worldgen.jar",
        mod("worldgen", ""),
        Map.of(
            "data/worldgen/worldgen/biome/hot.json",
            "{}",
            "data/worldgen/enchantment/spark.json",
            "{}",
            "data/worldgen/recipe/thing.json",
            "{}"),
        "worldgen.jar is refused: mod worldgen adds data pack entries to enchantment, worldgen/biome,"
            + " which the game only reads when a world loads");
    expect(
        mismatches,
        directory,
        "depends.jar",
        mod("depends", "\"depends\": {\"missing-mod\": \">=1.0.0\"},"),
        "depends.jar is refused: it depends on missing-mod [>=1.0.0]");
    expect(
        mismatches,
        directory,
        "breaks.jar",
        mod("breaks", "\"breaks\": {\"fabric-api\": \"*\"},"),
        "breaks.jar is refused: it breaks with fabric-api, which is loaded");
    expect(
        mismatches,
        directory,
        "empty.jar",
        null,
        "empty.jar is refused: it has no fabric.mod.json");

    helper.assertValueEqual(mismatches, List.of(), "refusals that differ from the expected ones");
    for (String id :
        List.of(
            "only-client",
            "nested",
            "widener",
            "adapter",
            "foreign",
            "prelaunch",
            "worldgen",
            "depends",
            "breaks")) {
      helper.assertTrue(
          !FabricLoader.getInstance().isModLoaded(id), "the refused mod " + id + " was registered");
    }
    helper.succeed();
  }

  private static void expect(
      List<String> mismatches, Path directory, String name, String metadata, String refusal)
      throws IOException, ReflectiveOperationException {
    expect(mismatches, directory, name, metadata, Map.of(), refusal);
  }

  private static void expect(
      List<String> mismatches,
      Path directory,
      String name,
      String metadata,
      Map<String, String> files,
      String refusal)
      throws IOException, ReflectiveOperationException {
    Path jar = directory.resolve(name);
    try (OutputStream file = Files.newOutputStream(jar);
        ZipOutputStream zip = new ZipOutputStream(file)) {
      zip.putNextEntry(new ZipEntry(metadata == null ? "readme.txt" : "fabric.mod.json"));
      zip.write((metadata == null ? "" : metadata).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      for (Map.Entry<String, String> extra : new TreeMap<>(files).entrySet()) {
        zip.putNextEntry(new ZipEntry(extra.getKey()));
        zip.write(extra.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    String actual;
    try {
      actual = "loaded " + HotAdd.load(List.of(jar), Agent.instrumentation());
    } catch (HotAddRefusal e) {
      actual = e.getMessage();
    }
    if (!actual.equals(refusal)) {
      mismatches.add(name + ": " + actual);
    }
  }

  private static String mod(String id, String extra) {
    return "{\"schemaVersion\": 1, \"id\": \""
        + id
        + "\", \"version\": \"1.0.0\", "
        + extra
        + " \"name\": \""
        + id
        + "\"}";
  }
}
