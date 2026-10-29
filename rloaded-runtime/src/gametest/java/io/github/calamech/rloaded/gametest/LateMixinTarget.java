package io.github.calamech.rloaded.gametest;

/** A class loaded at startup, before any late Mixin exists. */
public class LateMixinTarget {
  /** An instance created at startup; a late Mixin must change it too. */
  public static final LateMixinTarget EXISTING = new LateMixinTarget();

  /**
   * A method present from the start.
   *
   * @return the name of this target
   */
  public String name() {
    return "target";
  }
}
