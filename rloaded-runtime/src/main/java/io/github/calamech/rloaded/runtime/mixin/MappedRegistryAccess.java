package io.github.calamech.rloaded.runtime.mixin;

import it.unimi.dsi.fastutil.objects.ObjectList;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.component.DataComponentLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches the state a registry changes when it freezes.
 *
 * @param <T> the type of the registry's entries
 */
@Mixin(MappedRegistry.class)
public interface MappedRegistryAccess<T> {
  /**
   * Tells whether the registry refuses new entries.
   *
   * @return whether the registry is frozen
   */
  @Accessor("frozen")
  boolean rloaded$frozen();

  /**
   * Sets whether the registry refuses new entries.
   *
   * @param frozen whether the registry is frozen
   */
  @Accessor("frozen")
  void rloaded$setFrozen(boolean frozen);

  /**
   * Returns the holders created by new entries before they are registered.
   *
   * @return the holders by value, or {@code null} once the registry is frozen
   */
  @Accessor("unregisteredIntrusiveHolders")
  Map<T, Holder.Reference<T>> rloaded$unregisteredIntrusiveHolders();

  /**
   * Sets the holders created by new entries before they are registered.
   *
   * @param holders the holders by value, or {@code null} to refuse new intrusive holders
   */
  @Accessor("unregisteredIntrusiveHolders")
  void rloaded$setUnregisteredIntrusiveHolders(Map<T, Holder.Reference<T>> holders);

  /**
   * Returns the holder of each registered value.
   *
   * @return the holders by value
   */
  @Accessor("byValue")
  Map<T, Holder.Reference<T>> rloaded$byValue();

  /**
   * Returns the holders in raw id order.
   *
   * @return the holders, by raw id
   */
  @Accessor("byId")
  ObjectList<Holder.Reference<T>> rloaded$byId();

  /**
   * Sets the component lookup built when the registry freezes.
   *
   * @param lookup the new lookup
   */
  @Accessor("componentLookup")
  void rloaded$setComponentLookup(DataComponentLookup<T> lookup);

  /** Gives every holder the tags the registry has for it. */
  @Invoker("refreshTagsInHolders")
  void rloaded$refreshTagsInHolders();
}
