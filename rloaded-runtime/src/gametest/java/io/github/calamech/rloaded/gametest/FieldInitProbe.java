package io.github.calamech.rloaded.gametest;

/** An interface that a late Mixin adds to armor stands, to read a field it also adds. */
public interface FieldInitProbe {
  /**
   * Reads the field the late Mixin adds with an initializer.
   *
   * @return the field's value, or {@code null} on an object built before the Mixin
   */
  String rloadedMarker();
}
