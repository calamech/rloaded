package io.github.calamech.rloaded.runtime.engine;

import io.github.calamech.rloaded.core.admission.JarClassifier;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;

/** The rules a mod jar must pass to be added to this running game. */
public final class Admission {
  // Fabric Loader's own adapter, and the one Fabric Language Kotlin brings in the base set.
  private static final Set<String> BASE_SET_ADAPTERS = Set.of("default", "kotlin");

  private Admission() {}

  /**
   * The static classifier, with the data pack registries this game reads only when a world loads:
   * the game's own and those other mods registered, by their folder under {@code
   * data/<namespace>/}.
   */
  public static JarClassifier classifier() {
    Set<String> registries =
        Stream.concat(
                DynamicRegistries.getWorldRegistries().stream(),
                RegistryDataLoader.DIMENSION_REGISTRIES.stream())
            .map(registry -> Registries.elementsDirPath(registry.key()))
            .collect(Collectors.toUnmodifiableSet());
    return new JarClassifier(registries, BASE_SET_ADAPTERS);
  }
}
