package io.github.calamech.rloaded.runtime.engine;

import io.github.calamech.rloaded.core.admission.JarProblem;
import io.github.calamech.rloaded.core.admission.Refusal;
import io.github.calamech.rloaded.protocol.Outcome;
import io.github.calamech.rloaded.runtime.engine.LoaderBridge.ModJar;
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
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
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
 * unchecked exception.
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
    List<ModJar> mods = new ArrayList<>();
    for (Path jar : jars) {
      mods.add(LoaderBridge.read(jar));
    }
    List<ModJar> ordered = dependencyOrder(checked(mods));
    try {
      for (ModJar mod : ordered) {
        LoaderBridge.register(mod);
      }
      for (ModJar mod : ordered) {
        for (String config : LoaderBridge.mixinConfigs(mod)) {
          LateMixins.apply(config, instrumentation);
        }
      }
    } catch (ReflectiveOperationException | UnmodifiableClassException e) {
      throw new IllegalStateException("cannot register the mods " + ids(ordered), e);
    }
    runEntrypoints(ordered);
    return ids(ordered);
  }

  private static Map<String, ModJar> checked(List<ModJar> mods) throws HotAddRefusal {
    Map<String, ModJar> byId = new LinkedHashMap<>();
    for (ModJar mod : mods) {
      if (FabricLoader.getInstance().isModLoaded(mod.id())) {
        throw new HotAddRefusal(mod.path(), "a mod with id " + mod.id() + " is already loaded");
      }
      if (byId.put(mod.id(), mod) != null) {
        throw new HotAddRefusal(mod.path(), "another jar of the add has id " + mod.id());
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
    if (!mod.metadata().getJars().isEmpty()) {
      throw new HotAddRefusal(
          mod.path(), "it contains nested jars, which cannot be added to a running game yet");
    }
    if (mod.metadata().getClassTweaker() != null) {
      throw new HotAddRefusal(
          mod.path(),
          "it widens access to game classes, which cannot be done in a running game yet");
    }
    switch (Admission.classifier().classify(mod.path())) {
      case Outcome.Ok<?> ok -> {
        List<?> refusals = (List<?>) ok.getValue();
        if (!refusals.isEmpty()) {
          throw new HotAddRefusal(
              mod.path(),
              refusals.stream()
                  .map(refusal -> ((Refusal) refusal).getReason())
                  .collect(Collectors.joining("; ")));
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
      for (ModInitializer main : entrypoints("main", ModInitializer.class, mods)) {
        main.onInitialize();
      }
      if (LoaderBridge.environment() == EnvType.CLIENT) {
        entrypoints("client", ClientModInitializer.class, mods)
            .forEach(ClientModInitializer::onInitializeClient);
      } else {
        entrypoints("server", DedicatedServerModInitializer.class, mods)
            .forEach(DedicatedServerModInitializer::onInitializeServer);
      }
    } finally {
      registries.forEach(RegistryThaw::freeze);
    }
  }

  private static <T> List<T> entrypoints(String key, Class<T> type, List<ModJar> mods) {
    List<String> order = ids(mods);
    List<EntrypointContainer<T>> containers =
        FabricLoader.getInstance().getEntrypointContainers(key, type).stream()
            .filter(container -> order.contains(container.getProvider().getMetadata().getId()))
            .sorted(
                Comparator.comparingInt(
                    container -> order.indexOf(container.getProvider().getMetadata().getId())))
            .toList();
    return containers.stream().map(EntrypointContainer::getEntrypoint).toList();
  }

  private static List<String> ids(List<ModJar> mods) {
    return mods.stream().map(ModJar::id).toList();
  }
}
