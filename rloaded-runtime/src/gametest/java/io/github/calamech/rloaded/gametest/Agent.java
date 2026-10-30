package io.github.calamech.rloaded.gametest;

import java.lang.instrument.Instrumentation;
import java.util.Optional;

/** Reaches the Rloaded agent the game test server was started with. */
final class Agent {
  private Agent() {}

  static Instrumentation instrumentation() throws ReflectiveOperationException {
    Class<?> agentState = Class.forName("io.github.calamech.rloaded.agent.AgentState", true, ClassLoader.getSystemClassLoader());
    Optional<?> instrumentation = (Optional<?>) agentState.getMethod("instrumentation").invoke(null);
    return (Instrumentation) instrumentation.orElseThrow(() -> new IllegalStateException("start the server with the Rloaded agent"));
  }
}
