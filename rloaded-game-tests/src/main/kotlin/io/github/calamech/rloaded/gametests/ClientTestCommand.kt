package io.github.calamech.rloaded.gametests

import java.io.File
import java.nio.file.Path

// The launcher's command loads the base set and the runtime mod; the tests add their own mods,
// turn Fabric's client game tests on, and use OpenGL, which every runner and virtual display has.
internal fun withTestMods(
    command: List<String>,
    testMods: List<Path>,
    report: Path,
): List<String> {
    val extraMods = testMods.joinToString(File.pathSeparator)
    val properties = listOf("-Dfabric.client.gametest", "-Drloaded.client-game-test.report=$report")
    val withMods = command.map { if (it.startsWith(ADD_MODS)) "$it${File.pathSeparator}$extraMods" else it }
    require(withMods != command) { "the launch command has no $ADD_MODS argument" }
    return listOf(withMods.first()) + properties + withMods.drop(1) + listOf("--graphicsBackend", "opengl")
}

private const val ADD_MODS = "-Dfabric.addMods="
