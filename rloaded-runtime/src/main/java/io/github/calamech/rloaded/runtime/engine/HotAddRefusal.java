package io.github.calamech.rloaded.runtime.engine;

import java.nio.file.Path;

/** A mod jar that {@link HotAdd} refuses before anything of it runs. */
public final class HotAddRefusal extends Exception {
  private static final long serialVersionUID = 1L;

  /** The refused jar. */
  private final transient Path jar;

  HotAddRefusal(Path jar, String reason) {
    super(jar.getFileName() + " is refused: " + reason);
    this.jar = jar;
  }

  /** The refused jar. */
  public Path jar() {
    return jar;
  }
}
