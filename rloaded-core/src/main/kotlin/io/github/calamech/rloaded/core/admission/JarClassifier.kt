package io.github.calamech.rloaded.core.admission

import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.traverse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Why a mod cannot be added to a running game, found in its jar before anything is loaded. */
public sealed interface Refusal {
    /** The mod the rule applies to: the jar's own mod, or one nested in it. */
    public val modId: String

    /** Why, in words for the admin's log. */
    public val reason: String

    /** A `preLaunch` entrypoint runs before the game starts, which has already happened. */
    public data class PreLaunch(
        override val modId: String,
    ) : Refusal {
        override val reason: String get() = "mod $modId has a preLaunch entrypoint, which runs before the game starts"
    }

    /** Language adapters the mod declares or needs beyond the base set's; the game reads them once, at start. */
    public data class LanguageAdapter(
        override val modId: String,
        val adapters: List<String>,
    ) : Refusal {
        override val reason: String get() =
            "mod $modId needs the language adapters ${adapters.joinToString()}, which only work from startup"
    }

    /** Data pack registries the mod adds entries to; the game reads them only when a world loads. */
    public data class DatapackRegistry(
        override val modId: String,
        val registries: List<String>,
    ) : Refusal {
        override val reason: String get() =
            "mod $modId adds data pack entries to ${registries.joinToString()}, which the game only reads when a world loads"
    }
}

/** What the classifier read in a jar: its mod, where the mod runs, and every refusal. */
public data class JarReport(
    val modId: String,
    /** From the `environment` of its `fabric.mod.json`: `client`, `server`, or both for `*` or none. */
    val side: Side,
    /** The refusals of the jar and of the jars nested in it; empty when none of the rules refuses it. */
    val refusals: List<Refusal>,
)

/** Why a jar cannot be classified. A mod that cannot be classified is not added. */
public sealed interface JarProblem {
    /** The jar, or the nested jar, the problem is in. */
    public val jar: String

    /** What is wrong, in words for the admin's log. */
    public val description: String

    /** The jar, or a jar nested in it, cannot be read as a zip file. */
    public data class Unreadable(
        override val jar: String,
        val reason: String,
    ) : JarProblem {
        override val description: String get() = "$jar cannot be read: $reason"
    }

    /** The jar has no `fabric.mod.json`. */
    public data class NotAMod(
        override val jar: String,
    ) : JarProblem {
        override val description: String get() = "$jar has no fabric.mod.json"
    }

    /** The `fabric.mod.json` of the jar is not what Fabric Loader reads. */
    public data class Malformed(
        override val jar: String,
        val reason: String,
    ) : JarProblem {
        override val description: String get() = "the fabric.mod.json of $jar is invalid: $reason"
    }

    /** A file is larger than [JarClassifier.MAX_FILE_BYTES], or jars nest deeper than [JarClassifier.MAX_DEPTH]. */
    public data class TooLarge(
        override val jar: String,
        val file: String,
    ) : JarProblem {
        override val description: String get() = "$jar holds $file beyond the limits Rloaded reads"
    }
}

/**
 * Reads a mod jar, and the jars nested in it, for what a running game cannot take: a `preLaunch`
 * entrypoint, a language adapter outside [languageAdapters], or entries for one of
 * [datapackRegistries]. Registries are named as their folders under `data/<namespace>/`, such as
 * `worldgen/biome`; the game knows the list, including the registries other mods add.
 */
public class JarClassifier(
    private val datapackRegistries: Set<String>,
    private val languageAdapters: Set<String>,
) {
    /** Reads [jar] and the jars nested in it. */
    public fun classify(jar: Path): Outcome<JarReport, JarProblem> =
        try {
            Files.newInputStream(jar).use { classify(jar.fileName.toString(), it, 0) }
        } catch (e: IOException) {
            Outcome.Err(JarProblem.Unreadable(jar.fileName.toString(), e.message ?: e.javaClass.simpleName))
        }

    private fun classify(
        name: String,
        input: InputStream,
        depth: Int,
    ): Outcome<JarReport, JarProblem> =
        if (depth > MAX_DEPTH) {
            Outcome.Err(JarProblem.TooLarge(name, "nested jars"))
        } else {
            read(name, input).flatMap { contents ->
                metadata(name, contents).flatMap { metadata ->
                    metadata.nested
                        .traverse { file ->
                            val bytes = contents.files[file]
                            if (bytes == null) {
                                Outcome.Err(JarProblem.Malformed(name, "nested jar $file is missing"))
                            } else {
                                classify("$name!/$file", bytes.inputStream(), depth + 1)
                            }
                        }.map { nested ->
                            JarReport(metadata.id, metadata.side, refusals(metadata, contents.names) + nested.flatMap { it.refusals })
                        }
                }
            }
        }

    private fun refusals(
        metadata: ModMetadata,
        names: List<String>,
    ): List<Refusal> {
        val adapters = (metadata.declaredAdapters + metadata.usedAdapters.filter { it !in languageAdapters }).distinct().sorted()
        val registries = names.mapNotNull { registryOf(it) }.distinct().sorted()
        return listOfNotNull(
            Refusal.PreLaunch(metadata.id).takeIf { metadata.preLaunch },
            Refusal.LanguageAdapter(metadata.id, adapters).takeIf { adapters.isNotEmpty() },
            Refusal.DatapackRegistry(metadata.id, registries).takeIf { registries.isNotEmpty() },
        )
    }

    // data/<namespace>/<registry>/<entry>: the registry is the longest known folder the path starts with.
    private fun registryOf(entry: String): String? {
        val parts = entry.split('/')
        if (parts.size < MIN_DATA_PARTS || parts[0] != "data") return null
        val inNamespace = parts.drop(2).joinToString("/")
        return datapackRegistries.filter { inNamespace.startsWith("$it/") }.maxByOrNull { it.length }
    }

    /** Entry names, and the bytes of `fabric.mod.json` and of every jar inside. */
    private class Contents(
        val names: List<String>,
        val files: Map<String, ByteArray>,
    )

    private fun read(
        name: String,
        input: InputStream,
    ): Outcome<Contents, JarProblem> =
        try {
            ZipInputStream(input).use { readEntries(name, it) }
        } catch (e: ZipException) {
            Outcome.Err(JarProblem.Unreadable(name, e.message ?: "not a zip file"))
        } catch (e: IOException) {
            Outcome.Err(JarProblem.Unreadable(name, e.message ?: e.javaClass.simpleName))
        }

    private fun readEntries(
        name: String,
        zip: ZipInputStream,
    ): Outcome<Contents, JarProblem> {
        val names = mutableListOf<String>()
        val files = mutableMapOf<String, ByteArray>()
        while (true) {
            val entry = zip.nextEntry ?: break
            names += entry.name
            if (entry.name == METADATA || entry.name.endsWith(".jar")) {
                val bytes = zip.readNBytes(MAX_FILE_BYTES + 1)
                if (bytes.size > MAX_FILE_BYTES) return Outcome.Err(JarProblem.TooLarge(name, entry.name))
                files[entry.name] = bytes
            }
        }
        return Outcome.Ok(Contents(names, files))
    }

    private class ModMetadata(
        val id: String,
        val side: Side,
        val preLaunch: Boolean,
        val declaredAdapters: List<String>,
        val usedAdapters: List<String>,
        val nested: List<String>,
    )

    private fun metadata(
        name: String,
        contents: Contents,
    ): Outcome<ModMetadata, JarProblem> {
        val bytes = contents.files[METADATA] ?: return Outcome.Err(JarProblem.NotAMod(name))
        return try {
            val root = JSON.parseToJsonElement(bytes.decodeToString()) as? JsonObject
            val id = (root?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val environment = (root?.get("environment") as? JsonPrimitive)?.content ?: "*"
            val side = SIDES[environment]
            if (root == null || id == null) {
                Outcome.Err(JarProblem.Malformed(name, "no mod id"))
            } else if (side == null) {
                Outcome.Err(JarProblem.Malformed(name, "unknown environment $environment"))
            } else {
                val entrypoints = root["entrypoints"] as? JsonObject ?: JsonObject(emptyMap())
                Outcome.Ok(
                    ModMetadata(
                        id = id,
                        side = side,
                        preLaunch = (entrypoints["preLaunch"] as? JsonArray).orEmpty().isNotEmpty(),
                        declaredAdapters = (root["languageAdapters"] as? JsonObject)?.keys.orEmpty().toList(),
                        usedAdapters = entrypoints.values.flatMap { (it as? JsonArray).orEmpty() }.map { adapterOf(it) },
                        nested =
                            (root["jars"] as? JsonArray).orEmpty().mapNotNull {
                                (
                                    (it as? JsonObject)?.get(
                                        "file",
                                    ) as? JsonPrimitive
                                )?.content
                            },
                    ),
                )
            }
        } catch (e: SerializationException) {
            Outcome.Err(JarProblem.Malformed(name, e.message ?: "invalid JSON"))
        }
    }

    // An entrypoint is a class name, or an object naming its adapter; Fabric's own adapter is "default".
    private fun adapterOf(entrypoint: JsonElement): String =
        ((entrypoint as? JsonObject)?.get("adapter") as? JsonPrimitive)?.content ?: DEFAULT_ADAPTER

    /** Limits that keep a hostile jar from exhausting memory. */
    public companion object {
        /** Largest `fabric.mod.json` or nested jar read. */
        public const val MAX_FILE_BYTES: Int = 64 * 1024 * 1024

        /** Deepest nesting of jars read; Fabric mods rarely nest more than two levels. */
        public const val MAX_DEPTH: Int = 4

        private const val METADATA = "fabric.mod.json"
        private const val DEFAULT_ADAPTER = "default"
        private const val MIN_DATA_PARTS = 4
        private val JSON = Json { isLenient = true }

        // Fabric Loader's values for `environment`.
        private val SIDES = mapOf("*" to Side.BOTH, "client" to Side.CLIENT, "server" to Side.SERVER)
    }
}
