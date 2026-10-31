package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.admin.staging.StagingSettings
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.system.exitProcess

/**
 * Adds 20 mods in a row while two players are connected, through the same check as `addTest`, and
 * fails unless every add reaches both players and no log has an error line from the first add on.
 * The mods are generated, without code, so the check measures the add itself: load, data pack
 * reload, rebuilt entities, and two players through the configuration phase each time. Staging is
 * off on this server: `addTest` and `stagingTest` cover it.
 *
 * Arguments: client cache, server cache, run directory, agent jar, runtime mod jar, then the client
 * mod jars joined with the path separator.
 */
public fun main(arguments: Array<String>) {
    val generated = Path.of(arguments[RUN_ARGUMENT]).resolveSibling("soak-mods").createDirectories()
    val mods = (1..ADDS).map { index -> soakMod(generated, "soak-%02d".format(index)) }
    val steps = { _: List<AddPlayer> ->
        listOf("in the world" to emptyList<String>()) + mods.map { mod -> mod.title to listOf("rloaded add ${mod.projectId}") }
    }
    val passed = runAddCheck(arguments, AddPlan(mods, mapOf(StagingSettings.ENABLED_PROPERTY to "false"), steps, emptyList()) { true })
    exitProcess(if (passed) 0 else 1)
}

// A mod with an id, a language file and nothing else; its title is its mod id.
private fun soakMod(
    directory: Path,
    id: String,
): FakeMod {
    val jar = directory.resolve("$id.jar")
    ZipOutputStream(jar.outputStream()).use { zip ->
        zip.putNextEntry(ZipEntry("fabric.mod.json"))
        zip.write("""{"schemaVersion": 1, "id": "$id", "version": "1.0.0", "environment": "*"}""".toByteArray())
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("assets/$id/lang/en_us.json"))
        zip.write("""{"soak.$id": "$id"}""".toByteArray())
        zip.closeEntry()
    }
    // Modrinth ids have at most 16 letters and digits, the fake team id included.
    val project = id.replace("-", "")
    return FakeMod(project, "${project}v", id, jar)
}

private const val ADDS = 20
private const val RUN_ARGUMENT = 2
