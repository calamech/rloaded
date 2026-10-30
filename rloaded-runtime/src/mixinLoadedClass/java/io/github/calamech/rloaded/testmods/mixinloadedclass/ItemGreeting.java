package io.github.calamech.rloaded.testmods.mixinloadedclass;

/** Added to every item by this test mod's mixin, after items are loaded. */
public interface ItemGreeting {
  /** Counts the calls on this item, in a field the mixin adds. */
  String mixinLoadedClass$greet();
}
