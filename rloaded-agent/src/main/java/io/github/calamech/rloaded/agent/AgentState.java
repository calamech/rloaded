package io.github.calamech.rloaded.agent;

import java.lang.instrument.Instrumentation;
import java.util.Optional;

/** Holds the {@link Instrumentation} instance stored at startup. */
public final class AgentState {
  private static volatile Instrumentation instrumentation;

  private AgentState() {}

  static void attach(Instrumentation value) {
    instrumentation = value;
  }

  /**
   * Returns the instrumentation instance, or nothing when the JVM was started without the agent.
   *
   * @return the instrumentation instance stored by {@link RloadedAgent#premain}
   */
  public static Optional<Instrumentation> instrumentation() {
    return Optional.ofNullable(instrumentation);
  }

  static String statusLine(ClassRedefinition redefinition) {
    return "[Rloaded agent] attached; enhanced class redefinition: " + redefinition.description();
  }
}
