package io.github.calamech.rloaded.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The mods Rloaded loaded into this game since it started, as Modrinth project ids with their
 * version ids: at a join, in an open world, or before a world opened.
 */
public final class LoadedMods {
  private static final Map<String, String> LOADED = new ConcurrentHashMap<>();

  private LoadedMods() {}

  /** Records that {@code projectId} at {@code versionId} is loaded. */
  public static void record(String projectId, String versionId) {
    LOADED.put(projectId, versionId);
  }

  /** The mods loaded so far. */
  public static Map<String, String> snapshot() {
    return Map.copyOf(LOADED);
  }
}
