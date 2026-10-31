package io.github.calamech.rloaded.runtime.engine;

import net.minecraft.client.Minecraft;

/**
 * Lets mods added to a running game register key mappings. Fabric API refuses a key mapping once
 * the game's options exist, which is always the case for a mod added after the game started; while
 * a client add runs, the refusal is lifted, and afterwards the options are read again so the new
 * mappings join the controls screen with the keys the player saved for them.
 */
public final class LateKeyMappings {
  // The client thread is the only one to touch these.
  private static boolean open;
  private static int registered;

  private LateKeyMappings() {}

  /** Lets key mappings be registered until {@link #close}. Runs on the client thread. */
  public static void open() {
    open = true;
    registered = 0;
  }

  /**
   * Whether a key mapping registered now comes from a mod being added; counts it when so.
   *
   * @return whether the registration may go on although the options exist
   */
  public static boolean admit() {
    if (open) {
      registered++;
    }
    return open;
  }

  /** Ends the registrations and reads the options again if a mod added key mappings. */
  public static void close(Minecraft client) {
    open = false;
    if (registered > 0) {
      client.options.load();
    }
  }
}
