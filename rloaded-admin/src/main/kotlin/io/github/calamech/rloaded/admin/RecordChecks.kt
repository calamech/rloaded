package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.modrinth.Modrinth
import io.github.calamech.rloaded.modrinth.ModrinthError
import io.github.calamech.rloaded.modrinth.ModrinthProject
import io.github.calamech.rloaded.modrinth.ModrinthVersion
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** How proven a mod must be before an add accepts it. */
public data class ReputationRules(
    /** A version must have been published at least this long ago. */
    val minVersionAge: Duration,
    /** A project must have been downloaded at least this many times. */
    val minProjectDownloads: Long,
) {
    /** Bounds, defaults, and the system properties that carry the rules to the game. */
    public companion object {
        /** Hours a version must have been public, as a system property. */
        public const val VERSION_AGE_PROPERTY: String = "rloaded.reputation.min-version-age-hours"

        /** Downloads a project must have, as a system property. */
        public const val DOWNLOADS_PROPERTY: String = "rloaded.reputation.min-project-downloads"

        /** Allowed version ages, in hours: up to a year. Zero turns the rule off. */
        public val VERSION_AGE_HOURS: LongRange = 0L..8_760L

        /** Allowed download counts. Zero turns the rule off. */
        public val PROJECT_DOWNLOADS: LongRange = 0L..1_000_000_000L

        /** The age of a new server, in hours. */
        public const val DEFAULT_VERSION_AGE_HOURS: Long = 72

        /** The downloads of a new server. */
        public const val DEFAULT_PROJECT_DOWNLOADS: Long = 1_000

        /** Reads the rules from the system properties [lookup] returns; a missing or out of range value is refused. */
        public fun fromProperties(lookup: (String) -> String?): Outcome<ReputationRules, String> =
            bounded(lookup, VERSION_AGE_PROPERTY, VERSION_AGE_HOURS).flatMap { hours ->
                bounded(lookup, DOWNLOADS_PROPERTY, PROJECT_DOWNLOADS).map { downloads ->
                    ReputationRules(Duration.ofHours(hours), downloads)
                }
            }

        private fun bounded(
            lookup: (String) -> String?,
            property: String,
            range: LongRange,
        ): Outcome<Long, String> {
            val value = lookup(property)
            val number = value?.toLongOrNull()
            return when {
                value == null -> Outcome.Err("$property is not set; start the server with rloaded-server.jar")
                number == null || number !in range -> Outcome.Err("$property must be between ${range.first} and ${range.last}, not $value")
                else -> Outcome.Ok(number)
            }
        }
    }
}

/** A version or project an add refuses for what Modrinth's records say about it. */
public sealed interface RecordFailure {
    /** What is wrong, in words for the admin. */
    public val reason: String

    /** The version is newer than the reputation rules allow. */
    public data class TooRecent(
        val projectId: String,
        val versionNumber: String,
        val publishedAt: Instant,
        val minAge: Duration,
    ) : RecordFailure {
        override val reason: String get() =
            "$projectId $versionNumber was published on $publishedAt, less than ${minAge.toHours()} hours ago"
    }

    /** The project has fewer downloads than the reputation rules ask for. */
    public data class TooFewDownloads(
        val projectId: String,
        val downloads: Long,
        val minDownloads: Long,
    ) : RecordFailure {
        override val reason: String get() = "$projectId has $downloads downloads, fewer than $minDownloads"
    }

    /** The project is in a category the compatibility database refuses. */
    public data class DeniedCategory(
        val projectId: String,
        val category: String,
        val why: String,
    ) : RecordFailure {
        override val reason: String get() = "$projectId is in the $category category: $why"
    }

    /** The compatibility database refuses the project. */
    public data class DeniedProject(
        val projectId: String,
        val why: String,
    ) : RecordFailure {
        override val reason: String get() = "$projectId cannot be added: $why"
    }

    /** The version was tested with Rloaded and failed. */
    public data class KnownBroken(
        val versionId: String,
        val rloaded: String,
        val note: String,
    ) : RecordFailure {
        override val reason: String get() = "version $versionId failed with Rloaded $rloaded: $note"
    }
}

/**
 * Checks the versions an add would bring against what Modrinth records about them: the
 * [reputation][rules] of each version and project, and the [compatibility database][compat]. Project
 * records are read once, and only when a rule needs them.
 */
public class RecordChecks(
    private val modrinth: Modrinth,
    private val rules: ReputationRules,
    private val compat: CompatDatabase,
    private val clock: Clock,
) {
    /** Every failure of [versions], all of them; an empty list means none. */
    public suspend fun failures(versions: List<ModrinthVersion>): Outcome<List<RecordFailure>, ModrinthError> {
        val now = clock.instant()
        val fromVersions =
            versions.flatMap { version ->
                listOfNotNull(
                    compat.deniedProjects[version.projectId]?.let { RecordFailure.DeniedProject(version.projectId, it) },
                    compat.versions[version.id]
                        ?.takeIf { it.status == CompatStatus.BROKEN }
                        ?.let { RecordFailure.KnownBroken(version.id, it.rloaded, it.note) },
                    RecordFailure
                        .TooRecent(version.projectId, version.versionNumber, version.publishedAt, rules.minVersionAge)
                        .takeIf { Duration.between(version.publishedAt, now) < rules.minVersionAge },
                )
            }
        if (rules.minProjectDownloads == 0L && compat.deniedCategories.isEmpty()) return Outcome.Ok(fromVersions)
        return modrinth.projects(versions.map { it.projectId }.distinct()).map { projects: Map<String, ModrinthProject> ->
            fromVersions + projects.values.sortedBy { it.id }.flatMap { project -> projectFailures(project) }
        }
    }

    private fun projectFailures(project: ModrinthProject): List<RecordFailure> =
        project.categories.mapNotNull { category ->
            compat.deniedCategories[category]?.let { RecordFailure.DeniedCategory(project.id, category, it) }
        } +
            listOfNotNull(
                RecordFailure
                    .TooFewDownloads(project.id, project.downloads, rules.minProjectDownloads)
                    .takeIf { project.downloads < rules.minProjectDownloads },
            )
}
