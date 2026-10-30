package io.github.calamech.rloaded.runtime.mixin;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Binds the value of a registry holder, as a registry does when it freezes.
 *
 * @param <T> the type of the holder's value
 */
@Mixin(Holder.Reference.class)
public interface HolderReferenceAccess<T> {
  /**
   * Binds the holder to its value.
   *
   * @param value the registered value
   */
  @Invoker("bindValue")
  void rloaded$bindValue(T value);
}
