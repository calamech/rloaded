package io.github.calamech.rloaded.gametest;

import io.github.calamech.rloaded.runtime.engine.HotAdd;
import io.github.calamech.rloaded.runtime.engine.HotAddRefusal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.gametest.framework.GameTestHelper;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Checks that a mod's nested jars load with it into the running server, at any depth, the way
 * Fabric loads them at startup: a nested mod the game already runs in a newer version is left out,
 * and one newer than the game's is refused.
 */
public final class NestedJarsGameTest {
  private static final String LIBRARY_CLASS = "io/github/calamech/rloaded/gametest/nested/Library";
  private static final String KEPT = "fabric-api-base";

  @GameTest
  public void nestedJarsLoadWithTheirMod(GameTestHelper helper) throws Exception {
    Path directory = Files.createTempDirectory("rloaded-nested");
    String running = version(KEPT);
    byte[] deep = jar(mod("nested-deep", ""), Map.of());
    byte[] library =
        jar(
            mod("nested-library", jars("META-INF/jars/deep.jar")),
            Map.of(
                LIBRARY_CLASS + ".class",
                emptyClass(LIBRARY_CLASS),
                "META-INF/jars/deep.jar",
                deep));
    byte[] older = jar(modVersion(KEPT, "0.0.1", ""), Map.of());
    Path outer = directory.resolve("outer.jar");
    Files.write(
        outer,
        jar(
            mod("nested-outer", jars("META-INF/jars/library.jar", "META-INF/jars/older.jar")),
            Map.of("META-INF/jars/library.jar", library, "META-INF/jars/older.jar", older)));

    List<String> loaded = HotAdd.load(List.of(outer), Agent.instrumentation());

    helper.assertValueEqual(
        loaded.stream().sorted().toList(),
        List.of("nested-deep", "nested-library", "nested-outer"),
        "loaded mods");
    for (String id : List.of("nested-outer", "nested-library", "nested-deep")) {
      helper.assertTrue(
          FabricLoader.getInstance().isModLoaded(id), "the loader does not list " + id);
    }
    helper.assertValueEqual(version(KEPT), running, "version of the mod the game already ran");
    Class<?> fromLibrary =
        Class.forName(
            LIBRARY_CLASS.replace('/', '.'), false, NestedJarsGameTest.class.getClassLoader());
    helper.assertValueEqual(
        fromLibrary
            .getProtectionDomain()
            .getCodeSource()
            .getLocation()
            .getPath()
            .contains(".fabric/processedMods/nested-library-1.0.0-"),
        true,
        "the library class comes from the extracted jar: " + fromLibrary.getProtectionDomain());

    Path newer = directory.resolve("newer.jar");
    Files.write(
        newer,
        jar(
            mod("nested-newer", jars("META-INF/jars/api.jar")),
            Map.of("META-INF/jars/api.jar", jar(modVersion(KEPT, "999.0.0", ""), Map.of()))));
    helper.assertValueEqual(
        refusal(newer),
        "newer.jar is refused: it nests "
            + KEPT
            + " 999.0.0, newer than the "
            + running
            + " the game runs",
        "refusal of a nested mod newer than the game's");
    helper.assertTrue(
        !FabricLoader.getInstance().isModLoaded("nested-newer"), "the refused mod was registered");

    Path plain = directory.resolve("plain.jar");
    Files.write(
        plain,
        jar(
            mod("nested-plain", jars("META-INF/jars/plain-lib.jar")),
            Map.of("META-INF/jars/plain-lib.jar", jar(null, Map.of("readme.txt", new byte[1])))));
    helper.assertValueEqual(
        refusal(plain),
        "plain.jar is refused: its nested jar META-INF/jars/plain-lib.jar has no fabric.mod.json",
        "refusal of a nested jar that is no mod");
    helper.succeed();
  }

  private static String refusal(Path jar) throws ReflectiveOperationException {
    try {
      return "loaded " + HotAdd.load(List.of(jar), Agent.instrumentation());
    } catch (HotAddRefusal e) {
      return e.getMessage();
    }
  }

  private static String version(String id) {
    return FabricLoader.getInstance()
        .getModContainer(id)
        .map(ModContainer::getMetadata)
        .orElseThrow(() -> new AssertionError(id + " is not loaded"))
        .getVersion()
        .getFriendlyString();
  }

  private static String jars(String... entries) {
    StringBuilder list = new StringBuilder("\"jars\": [");
    for (int i = 0; i < entries.length; i++) {
      list.append(i == 0 ? "" : ", ").append("{\"file\": \"").append(entries[i]).append("\"}");
    }
    return list.append("],").toString();
  }

  private static String mod(String id, String extra) {
    return modVersion(id, "1.0.0", extra);
  }

  private static String modVersion(String id, String version, String extra) {
    return "{\"schemaVersion\": 1, \"id\": \""
        + id
        + "\", \"version\": \""
        + version
        + "\", "
        + extra
        + " \"name\": \""
        + id
        + "\"}";
  }

  private static byte[] jar(String metadata, Map<String, byte[]> files) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      if (metadata != null) {
        zip.putNextEntry(new ZipEntry("fabric.mod.json"));
        zip.write(metadata.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
        zip.putNextEntry(new ZipEntry(file.getKey()));
        zip.write(file.getValue());
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static byte[] emptyClass(String name) {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(
        Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
    writer.visitEnd();
    return writer.toByteArray();
  }
}
