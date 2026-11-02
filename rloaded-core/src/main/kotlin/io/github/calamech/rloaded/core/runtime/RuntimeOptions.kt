package io.github.calamech.rloaded.core.runtime

/** The JVM options every game and server Rloaded starts gets, after the heap size. */
public object RuntimeOptions {
    /** Lets the agent add methods and fields to loaded classes (ADR 0012). */
    public const val ENHANCED_REDEFINITION: String = "-XX:+AllowEnhancedClassRedefinition"

    /**
     * Keeps the JIT compiler from inlining calls through the receiver types it profiled. After an
     * enhanced redefinition, those profiles still name the old version of the class, and inlining
     * through one aborts the JVM (ADR 0064, #114).
     */
    public const val NO_TYPE_PROFILE: String = "-XX:-UseTypeProfile"

    /** All of them, in order. */
    public val ALL: List<String> = listOf(ENHANCED_REDEFINITION, NO_TYPE_PROFILE)
}
