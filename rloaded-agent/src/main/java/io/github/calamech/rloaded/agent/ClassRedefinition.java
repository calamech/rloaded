package io.github.calamech.rloaded.agent;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.util.function.Function;

/**
 * Whether this JVM can add methods and fields to classes that are already loaded. Only the
 * JetBrains Runtime started with {@code -XX:+AllowEnhancedClassRedefinition} can.
 */
public enum ClassRedefinition {
  /** The flag is on: late mixins can change already loaded classes. */
  ACTIVE("active"),
  /** The JVM knows the flag but it is off. */
  DISABLED("inactive, start the JVM with -XX:+AllowEnhancedClassRedefinition"),
  /** The JVM does not know the flag: it is not a JetBrains Runtime. */
  UNSUPPORTED("unsupported, this JVM is not a JetBrains Runtime");

  static final String FLAG = "AllowEnhancedClassRedefinition";

  private final String description;

  ClassRedefinition(String description) {
    this.description = description;
  }

  String description() {
    return description;
  }

  /**
   * Reads the state of the running JVM.
   *
   * @return the state of enhanced class redefinition in this JVM
   */
  public static ClassRedefinition current() {
    HotSpotDiagnosticMXBean diagnostics =
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    return from(name -> diagnostics.getVMOption(name).getValue());
  }

  static ClassRedefinition from(Function<String, String> vmOption) {
    try {
      return Boolean.parseBoolean(vmOption.apply(FLAG)) ? ACTIVE : DISABLED;
    } catch (IllegalArgumentException unknownOption) {
      return UNSUPPORTED;
    }
  }
}
