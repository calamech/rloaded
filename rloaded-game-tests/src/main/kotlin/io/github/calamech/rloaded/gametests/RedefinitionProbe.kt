package io.github.calamech.rloaded.gametests

import java.lang.instrument.ClassDefinition
import java.lang.instrument.Instrumentation
import java.util.Optional

/**
 * The smallest program known to abort the JetBrains Runtime after an enhanced redefinition (#114).
 * [probeCall] runs hot with a [ProbeLeaf], so its receiver type profile names that class. Redefining
 * [ProbeMid] makes new versions of it and of [ProbeLeaf], but the profile keeps the old [ProbeLeaf].
 * When the JIT compiler then inlines [ProbeBase.v] for that old receiver, and resolves the call to
 * [ProbeBase.w] inside it, it meets a method of the old version and aborts the JVM.
 *
 * Runs in a process of its own, started with the agent, which holds the instrumentation.
 */
public fun main() {
    val instrumentation = agentInstrumentation()
    val mid = ProbeMid::class.java
    val bytes =
        mid.classLoader.getResourceAsStream(mid.name.replace('.', '/') + ".class")?.use { it.readBytes() }
            ?: error("the bytes of ${mid.name} are missing")
    var sink = ProbeOther().w().toLong()
    repeat(ROUNDS) {
        sink += warm(ProbeLeaf())
        instrumentation.redefineClasses(ClassDefinition(mid, bytes))
        sink += warm(ProbeLeaf())
    }
    println("Redefinition probe: $ROUNDS rounds, $sink")
}

private fun agentInstrumentation(): Instrumentation {
    val state = Class.forName("io.github.calamech.rloaded.agent.AgentState", true, ClassLoader.getSystemClassLoader())
    return (state.getMethod("instrumentation").invoke(null) as Optional<*>)
        .map { it as Instrumentation }
        .orElseThrow { IllegalStateException("start the probe with the Rloaded agent") }
}

private fun warm(receiver: ProbeBase): Long {
    var sum = 0L
    repeat(CALLS) { sum += probeCall(receiver) }
    return sum
}

private fun probeCall(receiver: ProbeBase): Int = receiver.v()

/** Its [v] makes a virtual call, which the compiler resolves for the receiver it inlined [v] for. */
internal abstract class ProbeBase {
    open fun v(): Int = w() + 1

    abstract fun w(): Int
}

/** The class the probe redefines. */
internal open class ProbeMid : ProbeBase() {
    override fun w(): Int = 2
}

/** Inherits from [ProbeMid], so it gets a new version too. */
internal open class ProbeLeaf : ProbeMid()

/** Overrides [v], so the call in [probeCall] is not bound by the class hierarchy alone. */
internal class ProbeOther : ProbeBase() {
    override fun v(): Int = OTHER_V

    override fun w(): Int = OTHER_W
}

private const val ROUNDS = 20
private const val CALLS = 200_000
private const val OTHER_V = 5
private const val OTHER_W = 3
