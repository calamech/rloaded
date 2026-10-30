package io.github.calamech.rloaded.gametest;

import io.github.calamech.rloaded.runtime.engine.LateMixins;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * Checks that a required Mixin configuration registered after startup changes a class that was
 * already loaded, without an error in the log.
 */
public final class LateMixinGameTest {
  @GameTest
  public void lateMixinAddsMembersToLoadedClass(GameTestHelper helper) throws Exception {
    Object existing = LateMixinTarget.EXISTING;
    helper.assertTrue(
        !(existing instanceof LateGreeting), "the target already implements LateGreeting");

    List<String> redefined;
    List<String> errors;
    try (ErrorLog log = ErrorLog.open()) {
      redefined = LateMixins.apply("rloaded-gametest.late.mixins.json", Agent.instrumentation());
      errors = log.lines();
    }

    helper.assertValueEqual(errors, List.of(), "error-level log lines");
    helper.assertValueEqual(
        redefined, List.of(LateMixinTarget.class.getName()), "redefined classes");
    helper.assertTrue(
        existing instanceof LateGreeting, "the existing instance does not implement LateGreeting");
    LateGreeting greeting = (LateGreeting) existing;
    helper.assertValueEqual(greeting.rloadedGreeting(), "hello from target #1", "first greeting");
    helper.assertValueEqual(greeting.rloadedGreeting(), "hello from target #2", "second greeting");
    helper.assertValueEqual(
        ((LateGreeting) (Object) new LateMixinTarget()).rloadedGreeting(),
        "hello from target #1",
        "new instance");
    helper.succeed();
  }
}
