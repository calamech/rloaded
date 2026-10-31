package io.github.calamech.rloaded.runtime.engine;

import java.nio.file.Path;

/**
 * A mod that {@link HotAdd} registered failed as it started. The game is then in a state only a
 * restart cleans.
 */
public final class HotAddFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** The jar of the failing mod. */
  private final transient Path jar;

  HotAddFailure(Path jar, String modId, Throwable cause) {
    super(modId + " failed as it started: " + cause, cause);
    this.jar = jar;
  }

  /** The jar of the failing mod. */
  public Path jar() {
    return jar;
  }
}
