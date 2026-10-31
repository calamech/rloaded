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
    require(command.any { it.startsWith(ADD_MODS) }) { "the launch command has no $ADD_MODS argument" }
    val extraMods = testMods.joinToString(File.pathSeparator)
    val withMods =
        if (testMods.isEmpty()) command else command.map { if (it.startsWith(ADD_MODS)) "$it${File.pathSeparator}$extraMods" else it }
    return listOf(withMods.first()) + properties.map { (name, value) -> "-D$name=$value" } + withMods.drop(1)
}

/**
 * The properties that run Fabric's client game tests, name the file passed tests go to, and list
 * the mod jars the tests load while the client runs, joined with the path separator.
 */
internal fun clientGameTestProperties(
    report: Path,
    hotAddMods: String,
): Map<String, String> =
    mapOf(
        "fabric.client.gametest" to "true",
        "rloaded.client-game-test.report" to report.toString(),
        "rloaded.hot-add-mods" to hotAddMods,
    )

private const val ADD_MODS = "-Dfabric.addMods="
