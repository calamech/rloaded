package io.github.calamech.rloaded.runtime.engine;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.entrypoint.EntrypointStorage;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.ParseMetadataException;
import net.fabricmc.loader.impl.metadata.VersionOverrides;

/**
 * The only class that touches Fabric Loader's internals, read in Fabric Loader 0.20.1: it reads a
 * mod jar's metadata, and registers the mod with the loader the way {@code FabricLoaderImpl} does at
 * startup, so the loader lists it and its entrypoints.
 */
public final class LoaderBridge {
  private LoaderBridge() {}

  /** A mod jar whose metadata was read, not registered yet. */
  public record ModJar(Path path, LoaderModMetadata metadata) {
    /** The mod id. */
    public String id() {
      return metadata.getId();
    }
  }

  /**
   * Reads the {@code fabric.mod.json} of {@code jar}.
   *
   * @throws HotAddRefusal if the jar has no valid metadata
   */
  public static ModJar read(Path jar) throws HotAddRefusal {
    try (FileSystem files = FileSystems.newFileSystem(jar);
        InputStream input = Files.newInputStream(files.getPath("fabric.mod.json"))) {
      DependencyOverrides overrides = new DependencyOverrides(FabricLoader.getInstance().getConfigDir());
      LoaderModMetadata metadata =
          ModMetadataParser.parseMetadata(input, jar.toString(), List.of(), new VersionOverrides(), overrides, false);
      return new ModJar(jar, metadata);
    } catch (NoSuchFileException e) {
      throw new HotAddRefusal(jar, "it has no fabric.mod.json");
    } catch (IOException | ParseMetadataException e) {
      throw new HotAddRefusal(jar, "its fabric.mod.json cannot be read: " + e.getMessage());
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
          throw new IllegalStateException("cannot register entrypoint " + entrypoint.getValue() + " of " + mod.id(), e);
        }
      }
    }
  }

  // ModCandidateImpl.createPlain is package-private; the loader calls it for a plain mod jar.
  private static ModCandidateImpl candidate(ModJar mod) throws ReflectiveOperationException {
    Method createPlain =
        ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class, LoaderModMetadata.class, boolean.class, Collection.class);
    createPlain.setAccessible(true);
    return (ModCandidateImpl) createPlain.invoke(null, List.of(mod.path()), mod.metadata(), false, List.of());
  }

  @SuppressWarnings("unchecked")
  private static List<ModContainerImpl> modList(FabricLoaderImpl loader) throws ReflectiveOperationException {
    return field(loader, "mods", List.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, ModContainerImpl> modMap(FabricLoaderImpl loader) throws ReflectiveOperationException {
    return field(loader, "modMap", Map.class);
  }

  private static <T> T field(FabricLoaderImpl loader, String name, Class<T> type) throws ReflectiveOperationException {
    Field field = FabricLoaderImpl.class.getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(loader));
  }
}
