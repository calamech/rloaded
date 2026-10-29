package io.github.calamech.rloaded.gametest;

/** An interface that the late Mixin adds to {@link LateMixinTarget}. */
public interface LateGreeting {
  /**
   * A method added by the late Mixin.
   *
   * @return a greeting built from a field the Mixin also adds
   */
  String rloadedGreeting();
}
