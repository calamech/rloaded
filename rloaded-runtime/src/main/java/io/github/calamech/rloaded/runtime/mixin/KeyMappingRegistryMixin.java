package io.github.calamech.rloaded.runtime.mixin;

import io.github.calamech.rloaded.runtime.engine.LateKeyMappings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fabric API refuses a key mapping when the game's options already exist. While a mod is added to
 * the running game, the check sees no options (see {@link LateKeyMappings}).
 */
@Mixin(targets = "net.fabricmc.fabric.impl.client.keymapping.KeyMappingRegistryImpl")
abstract class KeyMappingRegistryMixin {
  // Mixin calls it from the redirected read, which the compiler cannot see.
  @SuppressWarnings("UnusedMethod")
  @Redirect(
      method = "registerKeyMapping",
      at =
          @At(
              value = "FIELD",
              target = "Lnet/minecraft/client/Minecraft;options:Lnet/minecraft/client/Options;",
              opcode = Opcodes.GETFIELD))
  private static Options rloaded$noOptionsWhileAdding(Minecraft client) {
    return LateKeyMappings.admit() ? null : client.options;
  }
}
