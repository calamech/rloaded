package io.github.calamech.rloaded.agent;

import java.lang.instrument.Instrumentation;

/**
 * Java agent started with the game through {@code -javaagent}. It keeps the {@link Instrumentation}
 * instance so the runtime mod can transform classes that are already loaded.
 */
public final class RloadedAgent {
  private RloadedAgent() {}

  /**
   * Entry point called by the JVM before the game's main method.
   *
   * @param arguments the agent options, unused
   * @param instrumentation the instrumentation instance of this JVM
   */
  public static void premain(String arguments, Instrumentation instrumentation) {
    AgentState.attach(instrumentation);
    System.out.println(AgentState.statusLine(ClassRedefinition.current()));
  }
}
