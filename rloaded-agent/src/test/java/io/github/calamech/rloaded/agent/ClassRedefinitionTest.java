package io.github.calamech.rloaded.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ClassRedefinitionTest {
  @Test
  void readsTheFlag() {
    assertEquals(ClassRedefinition.ACTIVE, ClassRedefinition.from(name -> "true"));
    assertEquals(ClassRedefinition.DISABLED, ClassRedefinition.from(name -> "false"));
  }

  @Test
  void reportsAJvmWithoutTheFlag() {
    assertEquals(
        ClassRedefinition.UNSUPPORTED,
        ClassRedefinition.from(
            name -> {
              throw new IllegalArgumentException("VM option " + name + " does not exist");
            }));
  }

  @Test
  void asksTheRunningJvm() {
    // Tests run on JetBrains Runtime 25 without the flag.
    assertEquals(ClassRedefinition.DISABLED, ClassRedefinition.current());
  }

  @Test
  void storesTheInstrumentationAndPrintsTheStatus() {
    Instrumentation instrumentation =
        (Instrumentation)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, arguments) -> {
                  throw new UnsupportedOperationException(method.getName());
                });
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    PrintStream standardOutput = System.out;
    System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
    try {
      RloadedAgent.premain(null, instrumentation);
    } finally {
      System.setOut(standardOutput);
    }

    assertSame(instrumentation, AgentState.instrumentation().orElseThrow());
    assertEquals(
        "[Rloaded agent] attached; enhanced class redefinition: inactive, start the JVM with"
            + " -XX:+AllowEnhancedClassRedefinition"
            + System.lineSeparator(),
        output.toString(StandardCharsets.UTF_8));
  }
}
