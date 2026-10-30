package io.github.calamech.rloaded.gametest.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Records each client game test that finished, so the harness can tell a run where every test
 * passed from one where none ran.
 */
final class ClientTestReport {
  private ClientTestReport() {}

  static void passed(String test) {
    String file = System.getProperty("rloaded.client-game-test.report");
    if (file == null) {
      throw new IllegalStateException("run with the rloaded.client-game-test.report property");
    }
    try {
      Files.writeString(
          Path.of(file),
          test + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new IllegalStateException("cannot write the client game test report " + file, e);
    }
  }
}
