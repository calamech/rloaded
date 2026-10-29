package io.github.calamech.rloaded.gametest.mixin;

import io.github.calamech.rloaded.gametest.LateGreeting;
import io.github.calamech.rloaded.gametest.LateMixinTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Adds an interface, a method and a field to a class that is already loaded. */
@Mixin(LateMixinTarget.class)
public abstract class LateMixinTargetMixin implements LateGreeting {
  @Unique private int rloadedGreetings;

  @Override
  public String rloadedGreeting() {
    rloadedGreetings++;
    return "hello from " + ((LateMixinTarget) (Object) this).name() + " #" + rloadedGreetings;
  }
}
