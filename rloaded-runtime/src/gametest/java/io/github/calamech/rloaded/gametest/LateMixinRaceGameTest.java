package io.github.calamech.rloaded.gametest;

import io.github.calamech.rloaded.runtime.engine.LateMixins;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.minecraft.gametest.framework.GameTestHelper;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks that Mixin configurations applied after startup do not break classes that other threads
 * load at the same time.
 *
 * <p>Threads keep loading new classes through the game's class loader, so through Mixin's
 * transformer, while this test applies many empty configurations one after the other.
 */
public final class LateMixinRaceGameTest {
  private static final int CONFIGURATIONS = 300;
  private static final int LOADERS = 4;
  private static final long JOIN_SECONDS = 30;
  private static final Logger LOGGER = LoggerFactory.getLogger(LateMixinRaceGameTest.class);
  private static final String CLASS_PACKAGE = "io/github/calamech/rloaded/gametest/race/";

  @GameTest
  public void lateMixinsDoNotBreakConcurrentClassLoading(GameTestHelper helper) throws Exception {
    Path root = Files.createTempDirectory(Path.of(""), "late-mixin-race");
    List<String> configs = writeConfigurations(root);
    Files.createDirectories(root.resolve(CLASS_PACKAGE));
    FabricLauncherBase.getLauncher().addToClassPath(root);
    ClassLoader gameLoader = LateMixinRaceGameTest.class.getClassLoader();
    Instrumentation instrumentation = Agent.instrumentation();

    AtomicBoolean applying = new AtomicBoolean(true);
    AtomicInteger next = new AtomicInteger();
    AtomicInteger loaded = new AtomicInteger();
    Queue<String> failures = new ConcurrentLinkedQueue<>();
    List<Thread> loaders = new ArrayList<>();
    for (int i = 0; i < LOADERS; i++) {
      loaders.add(
          Thread.ofPlatform()
              .name("late-mixin-race-" + i)
              .start(
                  () -> {
                    while (applying.get()) {
                      String name = CLASS_PACKAGE + "Loaded" + next.getAndIncrement();
                      try {
                        Files.write(root.resolve(name + ".class"), emptyClass(name));
                        Class.forName(name.replace('/', '.'), false, gameLoader);
                        loaded.incrementAndGet();
                      } catch (IOException
                          | ReflectiveOperationException
                          | RuntimeException
                          | LinkageError e) {
                        failures.add(name + ": " + causes(e));
                      }
                    }
                  }));
    }

    List<String> redefined = new ArrayList<>();
    long start = System.nanoTime();
    try {
      for (String config : configs) {
        redefined.addAll(LateMixins.apply(config, instrumentation));
      }
    } finally {
      applying.set(false);
      for (Thread loader : loaders) {
        loader.join(TimeUnit.SECONDS.toMillis(JOIN_SECONDS));
      }
    }
    LOGGER.info(
        "Applied {} Mixin configurations in {} ms while {} classes loaded",
        CONFIGURATIONS,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
        loaded.get());
    deleteTree(root);

    helper.assertTrue(
        loaders.stream().noneMatch(Thread::isAlive), "a loader thread is still running");
    helper.assertValueEqual(
        failures.stream().limit(3).toList(),
        List.of(),
        failures.size() + " classes failed to load");
    helper.assertValueEqual(redefined, List.of(), "classes redefined for empty configurations");
    helper.assertTrue(
        loaded.get() >= CONFIGURATIONS,
        "only " + loaded.get() + " classes loaded while " + CONFIGURATIONS + " were applied");
    helper.succeed();
  }

  private static void deleteTree(Path root) throws IOException {
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }

  private static List<String> writeConfigurations(Path root) throws IOException {
    List<String> names = new ArrayList<>();
    for (int i = 0; i < CONFIGURATIONS; i++) {
      String name = "rloaded-gametest.race-" + i + ".mixins.json";
      Files.writeString(
          root.resolve(name),
          """
          {
            "required": true,
            "minVersion": "0.8",
            "package": "io.github.calamech.rloaded.gametest.race.config%d",
            "compatibilityLevel": "JAVA_25",
            "mixins": []
          }
          """
              .formatted(i),
          StandardCharsets.UTF_8);
      names.add(name);
    }
    return names;
  }

  private static byte[] emptyClass(String name) {
    ClassWriter writer = new ClassWriter(0);
    writer.visit(
        Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
    writer.visitEnd();
    return writer.toByteArray();
  }

  private static String causes(Throwable failure) {
    StringBuilder chain = new StringBuilder(failure.toString());
    for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
      chain.append(" <- ").append(cause);
    }
    return chain.toString();
  }
}
