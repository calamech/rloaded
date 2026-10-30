package io.github.calamech.rloaded.gametests

import java.io.File
import java.nio.file.Path

// The launcher's command loads the base set and the runtime mod; the tests add their own mods and
// the system properties that turn their tests on.
internal fun withTestMods(
    command: List<String>,
    testMods: List<Path>,
    properties: Map<String, String>,
): List<String> {
    val extraMods = testMods.joinToString(File.pathSeparator)
    val withMods = command.map { if (it.startsWith(ADD_MODS)) "$it${File.pathSeparator}$extraMods" else it }
    require(withMods != command) { "the launch command has no $ADD_MODS argument" }
    return listOf(withMods.first()) + properties.map { (name, value) -> "-D$name=$value" } + withMods.drop(1)
}

/** The properties that run Fabric's client game tests and name the file passed tests go to. */
internal fun clientGameTestProperties(report: Path): Map<String, String> =
    mapOf("fabric.client.gametest" to "true", "rloaded.client-game-test.report" to report.toString())

private const val ADD_MODS = "-Dfabric.addMods="
