package io.github.calamech.rloaded.core.admission

import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import io.github.calamech.rloaded.protocol.map
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs

class JarClassifierTest {
    @TempDir
    lateinit var directory: Path

    private val classifier =
        JarClassifier(setOf("worldgen/biome", "enchantment", "dimension", "spell"), setOf("default", "kotlin"))

    @Test
    fun `accepts a mod with ordinary entrypoints, recipes and tags`() {
        val jar =
            jar(
                mod("plain", """"entrypoints": {"main": ["a.Main", {"adapter": "kotlin", "value": "a.Kt"}], "client": ["a.Client"]}"""),
                "data/plain/recipe/thing.json" to "{}",
                "data/plain/tags/worldgen/biome/hot.json" to "{}",
                "assets/plain/lang/en_us.json" to "{}",
            )

        assertEquals(Outcome.Ok(emptyList()), refusals(jar))
    }

    @Test
    fun `refuses a preLaunch entrypoint`() {
        val jar = jar(mod("early", """"entrypoints": {"preLaunch": ["a.Early"]}"""))

        assertEquals(Outcome.Ok(listOf(Refusal.PreLaunch("early"))), refusals(jar))
    }

    @Test
    fun `refuses declared language adapters and entrypoints that need an adapter outside the base set`() {
        val jar =
            jar(
                mod(
                    "scala",
                    """"languageAdapters": {"scala": "a.Adapter"}, "entrypoints": {"main": [{"adapter": "clojure", "value": "a.Main"}]}""",
                ),
            )

        assertEquals(Outcome.Ok(listOf(Refusal.LanguageAdapter("scala", listOf("clojure", "scala")))), refusals(jar))
    }

    @Test
    fun `refuses entries for data pack registries, including registries other mods add`() {
        val jar =
            jar(
                mod("worldly"),
                "data/worldly/worldgen/biome/hot.json" to "{}",
                "data/minecraft/dimension/overworld.json" to "{}",
                "data/worldly/spell/fire.json" to "{}",
                "data/worldly/worldgen/biome_source/x.json" to "{}",
            )

        assertEquals(
            Outcome.Ok(listOf(Refusal.DatapackRegistry("worldly", listOf("dimension", "spell", "worldgen/biome")))),
            refusals(jar),
        )
    }

    @Test
    fun `reads the jars nested in a mod, and names the nested mod a rule refuses`() {
        val inner = jarBytes(mod("inner", """"entrypoints": {"preLaunch": ["b.Early"]}"""))
        val jar = jar(mod("outer", """"jars": [{"file": "META-INF/jars/inner.jar"}]"""), "META-INF/jars/inner.jar" to inner)

        assertEquals(Outcome.Ok(listOf(Refusal.PreLaunch("inner"))), refusals(jar))
    }

    @Test
    fun `cannot classify a jar that is not a readable mod`() {
        val notZip = directory.resolve("text.jar").also { it.writeText("not a zip") }

        assertEquals(Outcome.Err(JarProblem.NotAMod("text.jar")), classify(notZip))
        assertEquals(Outcome.Err(JarProblem.NotAMod("mod.jar")), classify(jar("readme.txt" to "hello")))
        assertIs<JarProblem.Malformed>((classify(jar("fabric.mod.json" to "{\"id\": ")) as Outcome.Err).error)
        assertEquals(Outcome.Err(JarProblem.Malformed("mod.jar", "no mod id")), classify(jar("fabric.mod.json" to "{}")))
        assertEquals(
            Outcome.Err(JarProblem.Malformed("mod.jar", "nested jar META-INF/jars/gone.jar is missing")),
            classify(jar(mod("outer", """"jars": [{"file": "META-INF/jars/gone.jar"}]"""))),
        )
    }

    @Test
    fun `stops reading jars nested deeper than the limit`() {
        var bytes = jarBytes(mod("level0"))
        repeat(JarClassifier.MAX_DEPTH + 1) { level ->
            bytes = jarBytes(mod("level${level + 1}", """"jars": [{"file": "n.jar"}]"""), "n.jar" to bytes)
        }
        val jar = directory.resolve("mod.jar").also { it.writeBytes(bytes) }

        assertIs<JarProblem.TooLarge>((classify(jar) as Outcome.Err).error)
    }

    @Test
    fun `explains each refusal and problem for the admin`() {
        assertEquals(
            listOf(
                "mod a has a preLaunch entrypoint, which runs before the game starts",
                "mod b needs the language adapters scala, which only work from startup",
                "mod c adds data pack entries to dimension, worldgen/biome, which the game only reads when a world loads",
            ),
            listOf(
                Refusal.PreLaunch("a"),
                Refusal.LanguageAdapter("b", listOf("scala")),
                Refusal.DatapackRegistry("c", listOf("dimension", "worldgen/biome")),
            ).map { it.reason },
        )
        assertEquals(
            listOf(
                "x.jar cannot be read: bad zip",
                "x.jar has no fabric.mod.json",
                "the fabric.mod.json of x.jar is invalid: no mod id",
                "x.jar holds big.jar beyond the limits Rloaded reads",
            ),
            listOf(
                JarProblem.Unreadable("x.jar", "bad zip"),
                JarProblem.NotAMod("x.jar"),
                JarProblem.Malformed("x.jar", "no mod id"),
                JarProblem.TooLarge("x.jar", "big.jar"),
            ).map { it.description },
        )
    }

    @Test
    fun `reads where the mod runs from its environment`() {
        val sides =
            listOf("\"environment\": \"client\"", "\"environment\": \"server\"", "\"environment\": \"*\"", "").map { field ->
                (classify(jar(mod("sided", field))) as Outcome.Ok).value.side
            }

        assertEquals(listOf(Side.CLIENT, Side.SERVER, Side.BOTH, Side.BOTH), sides)
        assertEquals(
            Outcome.Err(JarProblem.Malformed("mod.jar", "unknown environment both")),
            classify(jar(mod("sided", "\"environment\": \"both\""))),
        )
    }

    private fun classify(jar: Path) = classifier.classify(jar)

    private fun refusals(jar: Path) = classifier.classify(jar).map { it.refusals }

    private fun mod(
        id: String,
        fields: String = "",
    ): Pair<String, Any> =
        "fabric.mod.json" to """{"schemaVersion": 1, "id": "$id", "version": "1.0.0"${if (fields.isEmpty()) "" else ", $fields"}}"""

    private fun jar(vararg entries: Pair<String, Any>): Path = directory.resolve("mod.jar").also { it.writeBytes(jarBytes(*entries)) }

    private fun jarBytes(vararg entries: Pair<String, Any>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(if (content is ByteArray) content else content.toString().toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
