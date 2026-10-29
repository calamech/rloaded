package io.github.calamech.rloaded.runtime;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;

/**
 * Reaches the Rloaded agent, which the JVM loads from the system class path with {@code
 * -javaagent}. The runtime mod is built separately from the agent, so it calls the agent's two
 * public methods by name: {@code AgentState.instrumentation()} and {@code
 * ClassRedefinition.current()}.
 */
final class AgentBridge {
  private static final String AGENT_STATE = "io.github.calamech.rloaded.agent.AgentState";
  private static final String CLASS_REDEFINITION = "io.github.calamech.rloaded.agent.ClassRedefinition";

  private AgentBridge() {}

  /** The agent's instrumentation, or nothing when the JVM was started without the agent. */
  static Optional<Instrumentation> instrumentation() {
    return call(AGENT_STATE, "instrumentation")
        .flatMap(value -> (Optional<?>) value)
        .map(Instrumentation.class::cast);
  }

  /** The agent's view of enhanced class redefinition, or nothing without the agent. */
  static Optional<String> classRedefinition() {
    return call(CLASS_REDEFINITION, "current").map(Object::toString);
  }

  private static Optional<Object> call(String className, String method) {
    try {
      Class<?> type = Class.forName(className, true, ClassLoader.getSystemClassLoader());
      return Optional.ofNullable(type.getMethod(method).invoke(null));
    } catch (ClassNotFoundException missingAgent) {
      return Optional.empty();
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      // The agent and the runtime come from the same release, so this is a packaging defect.
      throw new IllegalStateException("The Rloaded agent does not match this runtime", e);
    }
  }
}
