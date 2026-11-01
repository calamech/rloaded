package io.github.calamech.rloaded.runtime.engine;

import io.github.calamech.rloaded.core.admission.JarProblem;
import io.github.calamech.rloaded.core.admission.JarReport;
import io.github.calamech.rloaded.core.admission.Refusal;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.engine.LoaderBridge.ModJar;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Loads mod jars into a game that already started, replaying for them what Fabric does at startup:
 * register the mods with the loader, apply their mixins, then run their entrypoints with the
 * registries open.
 *
 * <p>Runs on the game thread. A refusal happens before anything of the mods is registered; any
 * failure after that leaves the game in a state only a restart cleans, and is thrown as an
 * unchecked exception: a {@link HotAddFailure} naming the mod when one of its entrypoints fails.
 */
public final class HotAdd {
  private HotAdd() {}

  /**
   * Loads the mods in {@code jars}.
   *
   * @return the ids of the loaded mods, in the order their entrypoints ran
   * @throws HotAddRefusal if a jar is not a mod this method can load; nothing was changed then
   */
  public static List<String> load(List<Path> jars, Instrumentation instrumentation)
      throws HotAddRefusal {
    List<ModJar> ordered = new ArrayList<>();
    try {
      for (ModJar mod : dependencyOrder(checked(read(jars)))) {
        ordered.add(LoaderBridge.extracted(mod));
      }
      for (ModJar mod : ordered) {
        LoaderBridge.register(mod);
      }
      for (ModJar mod : ordered) {
        for (String config : LoaderBridge.mixinConfigs(mod)) {
          LateMixins.apply(config, instrumentation);
        }
      }
    } catch (ReflectiveOperationException | UnmodifiableClassException | IOException e) {
      throw new IllegalStateException("cannot register the mods " + ids(ordered), e);
    }
    runEntrypoints(ordered);
    return ids(ordered);
  }

  /**
   * Checks that {@link #load} would accept {@code jars}, without loading anything.
   *
   * @throws HotAddRefusal with the reason it would refuse them
   */
  public static void check(List<Path> jars) throws HotAddRefusal {
    checked(read(jars));
  }

  private static List<ModJar> read(List<Path> jars) throws HotAddRefusal {
    List<ModJar> mods = new ArrayList<>();
    for (Path jar : jars) {
      mods.addAll(LoaderBridge.read(jar));
    }
    return mods;
  }

  // Nested mods follow Fabric's rule at startup: the newest version of a mod wins. One the game
  // already runs in that version or a newer one is left out; a newer one cannot replace it.
  private static Map<String, ModJar> checked(List<ModJar> mods) throws HotAddRefusal {
    Map<String, ModJar> byId = new LinkedHashMap<>();
    for (ModJar mod : mods) {
      Optional<ModContainer> loaded = FabricLoader.getInstance().getModContainer(mod.id());
      if (mod.nested().isEmpty()) {
        if (loaded.isPresent()) {
          throw new HotAddRefusal(mod.path(), "a mod with id " + mod.id() + " is already loaded");
        }
        ModJar earlier = byId.get(mod.id());
        if (earlier != null && earlier.nested().isEmpty()) {
          throw new HotAddRefusal(mod.path(), "another jar of the add has id " + mod.id());
        }
      } else if (loaded.isPresent()) {
        Version running = loaded.get().getMetadata().getVersion();
        if (running.compareTo(mod.metadata().getVersion()) >= 0) {
          continue;
        }
        throw new HotAddRefusal(
            mod.path(),
            "it nests "
                + mod.id()
                + " "
                + mod.metadata().getVersion().getFriendlyString()
                + ", newer than the "
                + running.getFriendlyString()
                + " the game runs");
      }
      ModJar earlier = byId.get(mod.id());
      if (earlier == null
          || earlier.metadata().getVersion().compareTo(mod.metadata().getVersion()) < 0) {
        byId.put(mod.id(), mod);
      }
    }
    for (ModJar mod : mods) {
      refuseUnsupported(mod);
      refuseUnmetDependencies(mod, byId);
    }
    return byId;
  }

  private static void refuseUnsupported(ModJar mod) throws HotAddRefusal {
    EnvType environment = LoaderBridge.environment();
    if (!mod.metadata().loadsInEnvironment(environment)) {
      throw new HotAddRefusal(
          mod.path(), "it does not load on the " + environment.name().toLowerCase(Locale.ROOT));
    }
    if (mod.metadata().getClassTweaker() != null) {
      throw new HotAddRefusal(
          mod.path(),
          "it widens access to game classes, which cannot be done in a running game yet");
    }
    // The classifier reads nested jars with the jar that holds them.
    if (mod.nested().isPresent()) {
      return;
    }
    switch (Admission.classifier().classify(mod.path())) {
      case Outcome.Ok<?> ok -> {
        List<Refusal> refusals = ((JarReport) ok.getValue()).getRefusals();
        if (!refusals.isEmpty()) {
          throw new HotAddRefusal(
              mod.path(),
              refusals.stream().map(Refusal::getReason).collect(Collectors.joining("; ")));
        }
      }
      case Outcome.Err<?> err ->
          throw new HotAddRefusal(mod.path(), ((JarProblem) err.getError()).getDescription());
    }
  }

  private static void refuseUnmetDependencies(ModJar mod, Map<String, ModJar> batch)
      throws HotAddRefusal {
    for (ModDependency dependency : mod.metadata().getDependencies()) {
      ModJar inBatch = batch.get(dependency.getModId());
      boolean satisfied =
          inBatch != null
              ? dependency.matches(inBatch.metadata().getVersion())
              : LoaderBridge.satisfiedByLoaded(dependency);
      if (dependency.getKind() == ModDependency.Kind.DEPENDS && !satisfied) {
        throw new HotAddRefusal(
            mod.path(),
            "it depends on " + dependency.getModId() + " " + dependency.getVersionRequirements());
      }
      if (dependency.getKind() == ModDependency.Kind.BREAKS && satisfied) {
        throw new HotAddRefusal(
            mod.path(), "it breaks with " + dependency.getModId() + ", which is loaded");
      }
    }
  }

  // A mod runs after the mods of the same add it depends on, as Fabric orders entrypoints at
  // startup.
  private static List<ModJar> dependencyOrder(Map<String, ModJar> batch) {
    Set<String> ordered = new LinkedHashSet<>();
    for (String id : batch.keySet()) {
      visit(id, batch, ordered, new LinkedHashSet<>());
    }
    return ordered.stream().map(batch::get).toList();
  }

  private static void visit(
      String id, Map<String, ModJar> batch, Set<String> ordered, Set<String> visiting) {
    if (ordered.contains(id) || !visiting.add(id)) {
      return;
    }
    for (ModDependency dependency : batch.get(id).metadata().getDependencies()) {
      if (dependency.getKind() == ModDependency.Kind.DEPENDS
          && batch.containsKey(dependency.getModId())) {
        visit(dependency.getModId(), batch, ordered, visiting);
      }
    }
    ordered.add(id);
  }

  private static void runEntrypoints(List<ModJar> mods) {
    List<Registry<?>> registries = new ArrayList<>();
    BuiltInRegistries.REGISTRY.forEach(registries::add);
    registries.forEach(RegistryThaw::thaw);
    try {
      run(mods, "main", ModInitializer.class, ModInitializer::onInitialize);
      if (LoaderBridge.environment() == EnvType.CLIENT) {
        run(mods, "client", ClientModInitializer.class, ClientModInitializer::onInitializeClient);
      } else {
        run(
            mods,
            "server",
            DedicatedServerModInitializer.class,
            DedicatedServerModInitializer::onInitializeServer);
      }
    } finally {
      registries.forEach(RegistryThaw::freeze);
    }
  }

  // The game checks its registries with assertions: a mod that breaks them fails like one that
  // throws. Either names the mod.
  private static <T> void run(
      List<ModJar> mods, String key, Class<T> type, Consumer<T> initializer) {
    List<String> order = ids(mods);
    List<EntrypointContainer<T>> containers =
        FabricLoader.getInstance().getEntrypointContainers(key, type).stream()
            .filter(container -> order.contains(container.getProvider().getMetadata().getId()))
            .sorted(
                Comparator.comparingInt(
                    container -> order.indexOf(container.getProvider().getMetadata().getId())))
            .toList();
    for (EntrypointContainer<T> container : containers) {
      try {
        initializer.accept(container.getEntrypoint());
      } catch (RuntimeException | AssertionError e) {
        String id = container.getProvider().getMetadata().getId();
        throw new HotAddFailure(mods.get(order.indexOf(id)).path(), id, e);
      }
    }
  }

  private static List<String> ids(List<ModJar> mods) {
    return mods.stream().map(ModJar::id).toList();
  }
}
