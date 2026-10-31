package io.github.calamech.rloaded.runtime.engine;

import io.github.calamech.rloaded.runtime.mixin.HolderReferenceAccess;
import io.github.calamech.rloaded.runtime.mixin.MappedRegistryAccess;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;

/**
 * Opens a frozen registry for new entries and freezes it again.
 *
 * <p>{@code MappedRegistry.freeze} can only run once: a second call fails because the registry's
 * tags are already bound. Freezing again here repeats only the steps that new entries need: bind
 * their values, rebuild the component lookup, and give every holder its tags, which are none for a
 * new entry until tags reload. Entries are only appended; existing raw ids never change.
 */
public final class RegistryThaw {
  // Registries whose values create their holder in their constructor, such as blocks. The game
  // forgets which ones once they freeze, so they are noted before.
  private static final Set<ResourceKey<? extends Registry<?>>> INTRUSIVE =
      ConcurrentHashMap.newKeySet();

  private RegistryThaw() {}

  /**
   * Notes which built-in registries create holders in their values' constructors. Must run before
   * the registries freeze; the runtime mod calls it as it starts.
   */
  public static void rememberIntrusiveRegistries() {
    for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
      if (access(registry).rloaded$unregisteredIntrusiveHolders() != null) {
        INTRUSIVE.add(registry.key());
      }
    }
  }

  /**
   * Lets {@code registry} accept new entries, including entries such as blocks that create their
   * holder in their constructor, in the registries noted by {@link #rememberIntrusiveRegistries}.
   *
   * @throws IllegalStateException if the registry is not frozen
   */
  public static <T> void thaw(Registry<T> registry) {
    MappedRegistryAccess<T> access = access(registry);
    if (!access.rloaded$frozen()) {
      throw new IllegalStateException("registry " + registry.key().identifier() + " is not frozen");
    }
    if (INTRUSIVE.contains(registry.key())) {
      access.rloaded$setUnregisteredIntrusiveHolders(new IdentityHashMap<>());
    }
    access.rloaded$setFrozen(false);
  }

  /**
   * Freezes {@code registry} again after {@link #thaw}.
   *
   * @throws IllegalStateException if the registry is frozen already, or if a value created a holder
   *     but was never registered; the registry then stays open, so the value can still be
   *     registered
   */
  public static <T> void freeze(Registry<T> registry) {
    MappedRegistryAccess<T> access = access(registry);
    if (access.rloaded$frozen()) {
      throw new IllegalStateException(
          "registry " + registry.key().identifier() + " is already frozen");
    }
    Map<T, Holder.Reference<T>> unregistered = access.rloaded$unregisteredIntrusiveHolders();
    if (unregistered != null && !unregistered.isEmpty()) {
      throw new IllegalStateException(
          "values created holders in registry "
              + registry.key().identifier()
              + " but were never registered: "
              + unregistered.keySet());
    }
    access.rloaded$byValue().forEach(RegistryThaw::bindValue);
    access.rloaded$setUnregisteredIntrusiveHolders(null);
    access.rloaded$setComponentLookup(new DataComponentLookup<>(access.rloaded$byId()));
    access.rloaded$refreshTagsInHolders();
    access.rloaded$setFrozen(true);
  }

  @SuppressWarnings("unchecked")
  private static <T> void bindValue(T value, Holder.Reference<T> holder) {
    ((HolderReferenceAccess<T>) (Object) holder).rloaded$bindValue(value);
  }

  @SuppressWarnings("unchecked")
  private static <T> MappedRegistryAccess<T> access(Registry<T> registry) {
    return (MappedRegistryAccess<T>) registry;
  }
}
