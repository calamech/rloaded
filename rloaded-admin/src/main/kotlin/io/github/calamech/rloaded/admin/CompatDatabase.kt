package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What Rloaded knows about mods on Modrinth: categories and projects that cannot be added to a
 * running game, and versions tested with a verdict. Read from `compat/compat.json`.
 */
public data class CompatDatabase(
    /** Refused categories, with the reason. */
    val deniedCategories: Map<String, String>,
    /** Refused Modrinth project ids, with the reason. */
    val deniedProjects: Map<String, String>,
    /** Tested versions by Modrinth version id. */
    val versions: Map<String, TestedVersion>,
) {
    /** Reading the database. */
    public companion object {
        /** The only schema this version reads. */
        public const val SCHEMA: Int = 1

        private val JSON = Json { ignoreUnknownKeys = false }
        private val PRETTY =
            Json {
                prettyPrint = true
                encodeDefaults = true
            }

        /** The text of a database that lists [versions] and refuses no category or project. */
        internal fun versionsOnly(versions: Map<String, TestedVersion>): String =
            PRETTY.encodeToString(
                FileJson.serializer(),
                FileJson(
                    SCHEMA,
                    emptyList(),
                    emptyList(),
                    versions.map { (id, tested) -> VersionJson(id, tested.status, tested.rloaded, tested.note) },
                ),
            ) + "\n"

        /** Reads [text]; an unknown field, a missing one or a project listed twice refuses the whole file. */
        public fun parse(text: String): Outcome<CompatDatabase, String> =
            try {
                val file = JSON.decodeFromString(FileJson.serializer(), text)
                val projects = file.deniedProjects.map { it.projectId }
                val versions = file.versions.map { it.versionId }
                when {
                    file.schema != SCHEMA -> {
                        Outcome.Err("schema ${file.schema} is not $SCHEMA")
                    }

                    projects.size != projects.toSet().size -> {
                        Outcome.Err("a project is listed twice")
                    }

                    versions.size != versions.toSet().size -> {
                        Outcome.Err("a version is listed twice")
                    }

                    else -> {
                        Outcome.Ok(
                            CompatDatabase(
                                file.deniedCategories.associate { it.category to it.reason },
                                file.deniedProjects.associate { it.projectId to it.reason },
                                file.versions.associate { it.versionId to TestedVersion(it.status, it.rloaded, it.note) },
                            ),
                        )
                    }
                }
            } catch (e: SerializationException) {
                Outcome.Err(e.message ?: "invalid JSON")
            } catch (e: IllegalArgumentException) {
                Outcome.Err(e.message ?: "invalid JSON")
            }
    }
}

/** A version tested with Rloaded, and what the test found. */
public data class TestedVersion(
    val status: CompatStatus,
    /** The Rloaded version it was tested with. */
    val rloaded: String,
    val note: String,
)

/** What a test of a version found. Only [BROKEN] changes admission. */
@Serializable
public enum class CompatStatus {
    /** It worked; information for admins. */
    @SerialName("verified")
    VERIFIED,

    /** It failed; refused. */
    @SerialName("broken")
    BROKEN,
}

@Serializable
private data class FileJson(
    val schema: Int,
    val deniedCategories: List<CategoryJson>,
    val deniedProjects: List<ProjectJson>,
    val versions: List<VersionJson>,
)

@Serializable
private data class CategoryJson(
    val category: String,
    val reason: String,
)

@Serializable
private data class ProjectJson(
    val projectId: String,
    /** For people reading the file; the id decides. */
    val slug: String,
    val reason: String,
)

@Serializable
private data class VersionJson(
    val versionId: String,
    val status: CompatStatus,
    val rloaded: String,
    val note: String,
)
