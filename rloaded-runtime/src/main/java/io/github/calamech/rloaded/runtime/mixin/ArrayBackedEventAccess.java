package io.github.calamech.rloaded.runtime.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the listeners of a Fabric API event, so an add can tell which ones its mods registered.
 * The class is package-private in Fabric API; a field it renames fails the game at start.
 */
@Mixin(targets = "net.fabricmc.fabric.impl.base.event.ArrayBackedEvent")
public interface ArrayBackedEventAccess {
  /**
   * Returns the listeners in the order the event calls them.
   *
   * @return the event's array, which must not be changed
   */
  @Accessor("handlers")
  Object[] rloaded$handlers();
}
