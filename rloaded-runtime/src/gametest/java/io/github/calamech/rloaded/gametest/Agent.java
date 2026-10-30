package io.github.calamech.rloaded.gametest;

import java.lang.instrument.Instrumentation;
import java.util.Optional;

/** Reaches the Rloaded agent the game was started with. */
public final class Agent {
  private Agent() {}

  /** The agent's instrumentation; fails if the game was started without the agent. */
  public static Instrumentation instrumentation() throws ReflectiveOperationException {
    Class<?> agentState =
        Class.forName(
            "io.github.calamech.rloaded.agent.AgentState",
            true,
            ClassLoader.getSystemClassLoader());
    Optional<?> instrumentation =
        (Optional<?>) agentState.getMethod("instrumentation").invoke(null);
    return (Instrumentation)
        instrumentation.orElseThrow(
            () -> new IllegalStateException("start the server with the Rloaded agent"));
  }
}
