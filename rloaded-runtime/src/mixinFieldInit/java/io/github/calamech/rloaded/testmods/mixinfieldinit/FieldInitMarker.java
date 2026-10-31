package io.github.calamech.rloaded.testmods.mixinfieldinit;

/**
 * What the mod's mixin adds to every entity: a field set by the constructor, and a way to read it.
 */
public interface FieldInitMarker {
  /**
   * Reads the field the mixin adds.
   *
   * @return {@code "initialized"}, or {@code null} on an entity built before the mixin
   */
  String mixinFieldInit$marker();
}
