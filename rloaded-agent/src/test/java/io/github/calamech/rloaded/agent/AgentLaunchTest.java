package io.github.calamech.rloaded.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Starts a real JVM with the built agent jar, as the launcher and the server bootstrap do. */
class AgentLaunchTest {
  private static final String JAVA =
      Path.of(System.getProperty("java.home"), "bin", "java").toString();

  @Test
  void reportsActiveRedefinitionWhenTheFlagIsOn() throws Exception {
    List<String> lines = run("-XX:+AllowEnhancedClassRedefinition");

    assertEquals(
        List.of(
            "[Rloaded agent] attached; enhanced class redefinition: active",
            "instrumentation present: true"),
        lines);
  }

  @Test
  void reportsInactiveRedefinitionWhenTheFlagIsOff() throws Exception {
    List<String> lines = run();

    assertEquals(
        List.of(
            "[Rloaded agent] attached; enhanced class redefinition: inactive, start the JVM with"
                + " -XX:+AllowEnhancedClassRedefinition",
            "instrumentation present: true"),
        lines);
  }

  private static List<String> run(String... jvmFlags) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of(JAVA));
    command.addAll(List.of(jvmFlags));
    command.add("-javaagent:" + System.getProperty("rloaded.agentJar"));
    command.addAll(
        List.of("-cp", System.getProperty("java.class.path"), AgentProbe.class.getName()));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("the probe JVM did not exit within 30 seconds");
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), output);
    return output.lines().toList();
  }
}
