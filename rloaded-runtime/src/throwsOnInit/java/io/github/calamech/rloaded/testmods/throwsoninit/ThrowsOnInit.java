package io.github.calamech.rloaded.testmods.throwsoninit;

import net.fabricmc.api.ModInitializer;

/** A test mod that fails as it starts, which only running it can tell. */
public final class ThrowsOnInit implements ModInitializer {
  @Override
  public void onInitialize() {
    throw new IllegalStateException("throws-on-init fails as it starts, on purpose");
  }
}
