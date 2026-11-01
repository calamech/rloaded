package io.github.calamech.rloaded.runtime.engine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.lib.classtweaker.api.ClassTweaker;
import net.fabricmc.loader.impl.lib.classtweaker.api.ClassTweakerReader;
import net.fabricmc.loader.impl.lib.classtweaker.api.visitor.ClassTweakerVisitor;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.NestedJarEntry;
import net.fabricmc.loader.impl.metadata.ParseMetadataException;
import net.fabricmc.loader.impl.metadata.VersionOverrides;

/**
 * The only class that touches Fabric Loader's internals, read in Fabric Loader 0.20.1: it reads a
 * mod jar's metadata, and registers the mod with the loader the way {@code FabricLoaderImpl} does
 * at startup, so the loader lists it and its entrypoints.
 */
public final class LoaderBridge {
  /** How deep jars may nest, as the jar classifier reads them. */
  public static final int MAX_DEPTH = 4;

  private static final int MAX_NESTED_BYTES = 64 * 1024 * 1024;
  private static final int HASH_BYTES = 8;

  private LoaderBridge() {}

  /**
   * A mod whose metadata was read, not registered yet: a jar of the add, or a jar nested in one,
   * whose bytes wait to be extracted. {@code path} is the add's jar either way, the one a refusal
   * names.
   */
  public record ModJar(Path path, LoaderModMetadata metadata, Optional<Nested> nested) {
    /** The mod id. */
    public String id() {
      return metadata.getId();
    }
  }

  /** A jar nested in a mod jar: its entry in the parent jar, and its bytes. */
  public static final class Nested {
    private final String entry;
    private final byte[] bytes;

    Nested(String entry, byte[] bytes) {
      this.entry = entry;
      this.bytes = bytes.clone();
    }

    /** The jar's path inside the jar that holds it. */
    public String entry() {
      return entry;
    }

    byte[] bytes() {
      return bytes.clone();
    }
  }

  /**
   * Reads the {@code fabric.mod.json} of {@code jar}, then of each jar it nests, at any depth up to
   * {@value #MAX_DEPTH}.
   *
   * @return the mod of {@code jar} first, then the nested mods
   * @throws HotAddRefusal if a jar has no valid metadata, or a nested jar is missing or too large
   */
  public static List<ModJar> read(Path jar) throws HotAddRefusal {
    try (FileSystem files = FileSystems.newFileSystem(jar)) {
      LoaderModMetadata metadata = metadata(jar, files.getPath("fabric.mod.json"), jar.toString());
      List<ModJar> mods = new ArrayList<>();
      mods.add(new ModJar(jar, metadata, Optional.empty()));
      for (NestedJarEntry entry : metadata.getJars()) {
        Path inner = files.getPath(entry.getFile());
        if (!Files.isRegularFile(inner)) {
          throw new HotAddRefusal(
              jar, "it lists the nested jar " + entry.getFile() + ", which it does not contain");
        }
        if (Files.size(inner) > MAX_NESTED_BYTES) {
          throw new HotAddRefusal(
              jar, "its nested jar " + entry.getFile() + " is larger than 64 MiB");
        }
        nested(jar, entry.getFile(), Files.readAllBytes(inner), 1, mods);
      }
      return mods;
    } catch (IOException e) {
      throw new HotAddRefusal(jar, "it cannot be read: " + e.getMessage());
    }
  }

  private static void nested(Path jar, String entry, byte[] bytes, int depth, List<ModJar> mods)
      throws HotAddRefusal, IOException {
    if (depth > MAX_DEPTH) {
      throw new HotAddRefusal(jar, "it nests jars deeper than " + MAX_DEPTH + " levels");
    }
    Map<String, byte[]> entries = zipEntries(bytes);
    byte[] descriptor = entries.get("fabric.mod.json");
    if (descriptor == null) {
      throw new HotAddRefusal(jar, "its nested jar " + entry + " has no fabric.mod.json");
    }
    LoaderModMetadata metadata = parse(jar, new ByteArrayInputStream(descriptor), entry);
    mods.add(new ModJar(jar, metadata, Optional.of(new Nested(entry, bytes))));
    for (NestedJarEntry inner : metadata.getJars()) {
      byte[] innerBytes = entries.get(inner.getFile());
      if (innerBytes == null) {
        throw new HotAddRefusal(
            jar,
            "its nested jar "
                + entry
                + " lists "
                + inner.getFile()
                + ", which it does not contain");
      }
      nested(jar, inner.getFile(), innerBytes, depth + 1, mods);
    }
  }

  // The entries of a nested jar, read in memory and bounded like the jar itself.
  private static Map<String, byte[]> zipEntries(byte[] jar) throws IOException {
    Map<String, byte[]> entries = new HashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
      for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        if (!entry.isDirectory()) {
          entries.put(entry.getName(), zip.readNBytes(MAX_NESTED_BYTES));
        }
      }
    }
    return entries;
  }

  private static LoaderModMetadata metadata(Path jar, Path descriptor, String source)
      throws HotAddRefusal {
    try (InputStream input = Files.newInputStream(descriptor)) {
      return parse(jar, input, source);
    } catch (NoSuchFileException e) {
      throw new HotAddRefusal(jar, "it has no fabric.mod.json");
    } catch (IOException e) {
      throw new HotAddRefusal(jar, "its fabric.mod.json cannot be read: " + e.getMessage());
    }
  }

  private static LoaderModMetadata parse(Path jar, InputStream input, String source)
      throws HotAddRefusal {
    try {
      DependencyOverrides overrides =
          new DependencyOverrides(FabricLoader.getInstance().getConfigDir());
      return ModMetadataParser.parseMetadata(
          input, source, List.of(), new VersionOverrides(), overrides, false);
    } catch (ParseMetadataException e) {
      throw new HotAddRefusal(
          jar, "the fabric.mod.json of " + source + " cannot be read: " + e.getMessage());
    }
  }

  /**
   * Writes a nested mod's jar where Fabric Loader extracts nested jars at startup, {@code
   * .fabric/processedMods} in the game folder, and returns the mod with that file.
   */
  public static ModJar extracted(ModJar mod) throws IOException {
    if (mod.nested().isEmpty()) {
      return mod;
    }
    byte[] bytes = mod.nested().get().bytes();
    Path directory =
        FabricLoader.getInstance().getGameDir().resolve(".fabric").resolve("processedMods");
    Files.createDirectories(directory);
    String hash = HexFormat.of().formatHex(sha256(bytes), 0, HASH_BYTES);
    Path file =
        directory.resolve(
            mod.id() + "-" + mod.metadata().getVersion().getFriendlyString() + "-" + hash + ".jar");
    if (!Files.exists(file) || !Arrays.equals(Files.readAllBytes(file), bytes)) {
      Path temporary = Files.createTempFile(directory, ".tmp-", ".jar");
      Files.write(temporary, bytes);
      Files.move(
          temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    return new ModJar(file, mod.metadata(), Optional.empty());
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("the JDK has no SHA-256", e);
    }
  }

  /**
   * Reads the class tweaker (or access widener) {@code mod} declares, without giving it to the
   * game.
   *
   * @return the parsed class tweaker, or empty when the mod declares none
   * @throws HotAddRefusal if the file is missing or cannot be read
   */
  public static Optional<ClassTweaker> classTweaker(ModJar mod) throws HotAddRefusal {
    String location = mod.metadata().getClassTweaker();
    if (location == null) {
      return Optional.empty();
    }
    ClassTweaker parsed = ClassTweaker.newInstance();
    read(mod, location, parsed);
    return Optional.of(parsed);
  }

  /**
   * Gives the class tweaker {@code mod} declares to the game's, which Fabric applies to each class
   * as it loads.
   *
   * @return the binary names of the classes it changes, to redefine those already loaded
   */
  public static Set<String> applyClassTweaker(ModJar mod) throws HotAddRefusal {
    Optional<ClassTweaker> parsed = classTweaker(mod);
    if (parsed.isEmpty()) {
      return Set.of();
    }
    read(mod, mod.metadata().getClassTweaker(), FabricLoaderImpl.INSTANCE.getClassTweaker());
    return parsed.get().getTargets().stream()
        .map(name -> name.replace('/', '.'))
        .collect(Collectors.toSet());
  }

  private static void read(ModJar mod, String location, ClassTweakerVisitor into)
      throws HotAddRefusal {
    byte[] bytes = entry(mod, location);
    try {
      ClassTweakerReader.create(into)
          .read(
              bytes,
              FabricLauncherBase.getLauncher().getMappingConfiguration().getRuntimeNamespace());
    } catch (RuntimeException e) {
      throw new HotAddRefusal(
          mod.path(), "its class tweaker " + location + " cannot be read: " + e.getMessage());
    }
  }

  // A file of the mod's own jar, nested or not.
  private static byte[] entry(ModJar mod, String location) throws HotAddRefusal {
    try {
      byte[] bytes;
      if (mod.nested().isPresent()) {
        bytes = zipEntries(mod.nested().get().bytes()).get(location);
      } else {
        try (FileSystem files = FileSystems.newFileSystem(mod.path())) {
          Path file = files.getPath(location);
          bytes = Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        }
      }
      if (bytes == null) {
        throw new HotAddRefusal(
            mod.path(), "it declares " + location + ", which its jar does not contain");
      }
      return bytes;
    } catch (IOException e) {
      throw new HotAddRefusal(mod.path(), "it cannot be read: " + e.getMessage());
    }
  }

  /** The environment this game runs in. */
  public static EnvType environment() {
    return FabricLoader.getInstance().getEnvironmentType();
  }

  /** The mixin configurations of {@code mod} for this environment. */
  public static Collection<String> mixinConfigs(ModJar mod) {
    return mod.metadata().getMixinConfigs(environment());
  }

  /** Whether a loaded mod, or one {@code provides} names, satisfies {@code dependency}. */
  public static boolean satisfiedByLoaded(ModDependency dependency) {
    return FabricLoader.getInstance()
        .getModContainer(dependency.getModId())
        .map(loaded -> dependency.matches(loaded.getMetadata().getVersion()))
        .orElse(false);
  }

  /**
   * Adds {@code mod}'s jar to the game's class path and registers it with Fabric Loader, with its
   * entrypoints. Nothing of the mod runs yet.
   */
  public static void register(ModJar mod) throws ReflectiveOperationException {
    FabricLauncherBase.getLauncher().addToClassPath(mod.path());
    ModContainerImpl container = new ModContainerImpl(candidate(mod));
    FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
    modList(loader).add(container);
    Map<String, ModContainerImpl> byId = modMap(loader);
    byId.put(mod.id(), container);
    for (String provided : mod.metadata().getProvides()) {
      byId.put(provided, container);
    }
    EntrypointStorage entrypoints = field(loader, "entrypointStorage", EntrypointStorage.class);
    @SuppressWarnings("unchecked")
    Map<String, LanguageAdapter> adapters = field(loader, "adapterMap", Map.class);
    for (String key : mod.metadata().getEntrypointKeys()) {
      for (EntrypointMetadata entrypoint : mod.metadata().getEntrypoints(key)) {
        try {
          entrypoints.add(container, key, entrypoint, adapters);
        } catch (Exception e) {
          throw new IllegalStateException(
              "cannot register entrypoint " + entrypoint.getValue() + " of " + mod.id(), e);
        }
      }
    }
  }

  // ModCandidateImpl.createPlain is package-private; the loader calls it for a plain mod jar.
  private static ModCandidateImpl candidate(ModJar mod) throws ReflectiveOperationException {
    Method createPlain =
        ModCandidateImpl.class.getDeclaredMethod(
            "createPlain", List.class, LoaderModMetadata.class, boolean.class, Collection.class);
    createPlain.setAccessible(true);
    return (ModCandidateImpl)
        createPlain.invoke(null, List.of(mod.path()), mod.metadata(), false, List.of());
  }

  @SuppressWarnings("unchecked")
  private static List<ModContainerImpl> modList(FabricLoaderImpl loader)
      throws ReflectiveOperationException {
    return field(loader, "mods", List.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ModContainerImpl> modMap(FabricLoaderImpl loader)
      throws ReflectiveOperationException {
    return field(loader, "modMap", Map.class);
  }

  private static <T> T field(FabricLoaderImpl loader, String name, Class<T> type)
      throws ReflectiveOperationException {
    Field field = FabricLoaderImpl.class.getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(loader));
  }
}
