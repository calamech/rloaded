package io.github.calamech.rloaded.agent;

/** Main class of the JVM started by {@link AgentLaunchTest}. */
public final class AgentProbe {
  private AgentProbe() {}

  /**
   * Prints whether the agent stored its instrumentation.
   *
   * @param arguments unused
   */
  public static void main(String[] arguments) {
    System.out.println("instrumentation present: " + AgentState.instrumentation().isPresent());
  }
}
