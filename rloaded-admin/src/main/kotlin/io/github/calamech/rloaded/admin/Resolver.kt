package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.modrinth.DependencyType
import io.github.calamech.rloaded.modrinth.Modrinth
import io.github.calamech.rloaded.modrinth.ModrinthDependency
import io.github.calamech.rloaded.modrinth.ModrinthError
import io.github.calamech.rloaded.modrinth.ModrinthVersion
import io.github.calamech.rloaded.modrinth.VersionType
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap

/** Why a mod cannot be turned into versions to add. */
public sealed interface ResolveError {
    /** What went wrong, in words for the admin. */
    public val reason: String

    /** Modrinth's records could not be read. */
    public data class Records(
        val error: ModrinthError,
    ) : ResolveError {
        override val reason: String get() = "Modrinth's records cannot be read: $error"
    }

    /** Modrinth has no such project. */
    public data class UnknownProject(
        val project: String,
    ) : ResolveError {
        override val reason: String get() = "Modrinth has no project $project"
    }

    /** The project has no version for Fabric and this game version. */
    public data class NoVersion(
        val project: String,
        val gameVersion: String,
    ) : ResolveError {
        override val reason: String get() = "$project has no Fabric version for Minecraft $gameVersion"
    }

    /** The project has only betas and alphas for this game version. */
    public data class NoRelease(
        val project: String,
    ) : ResolveError {
        override val reason: String get() = "$project has only beta or alpha versions, which are not added"
    }

    /** The version asked for is not one of the project's versions for Fabric and this game version. */
    public data class UnknownVersion(
        val project: String,
        val version: String,
    ) : ResolveError {
        override val reason: String get() = "$project has no Fabric version $version for this game"
    }

    /** The version asked for is a beta or an alpha. */
    public data class Prerelease(
        val project: String,
        val versionNumber: String,
    ) : ResolveError {
        override val reason: String get() = "$project $versionNumber is a beta or an alpha, which are not added"
    }

    /** The project is already installed; replacing a mod is not supported. */
    public data class AlreadyInstalled(
        val project: String,
    ) : ResolveError {
        override val reason: String get() = "$project is already installed"
    }

    /** A required dependency names neither a project nor a version on Modrinth. */
    public data class ExternalDependency(
        val versionId: String,
    ) : ResolveError {
        override val reason: String get() = "version $versionId needs a file from outside Modrinth"
    }

    /** A required dependency pins a version that is not for Fabric and this game version. */
    public data class DependencyNotForGame(
        val versionId: String,
    ) : ResolveError {
        override val reason: String get() = "the dependency version $versionId is not for Fabric on this game"
    }

    /** A version needs another version of a project than the one installed or chosen. */
    public data class Conflict(
        val projectId: String,
        val presentVersionId: String,
        val wantedVersionId: String,
    ) : ResolveError {
        override val reason: String get() =
            "a dependency needs version $wantedVersionId of $projectId, but version $presentVersionId is installed or chosen"
    }

    /** A version declares a project it does not work with, and that project is installed or added with it. */
    public data class Incompatible(
        val versionId: String,
        val projectId: String,
    ) : ResolveError {
        override val reason: String get() = "version $versionId does not work with $projectId, which would be installed with it"
    }

    /** The mod pulls in more dependencies than an add accepts. */
    public data class TooMany(
        val limit: Int,
    ) : ResolveError {
        override val reason: String get() = "the mod needs more than $limit mods"
    }
}

/**
 * Turns a mod an admin asks for into the Modrinth versions to add: the version asked for, or the
 * newest release for Fabric and [gameVersion], then its required dependencies, recursively, that are
 * not installed yet. Optional and embedded dependencies are left out; betas and alphas are refused.
 */
public class Resolver(
    internal val modrinth: Modrinth,
    internal val gameVersion: String,
) {
    /**
     * Resolves [project], an id or a slug, at [version], a version number or id, or its newest
     * release when null. [installed] maps the project ids already installed to their version ids.
     * On success the versions come dependencies first, the mod asked for last.
     */
    public suspend fun resolve(
        project: String,
        version: String?,
        installed: Map<String, String>,
    ): Outcome<List<ModrinthVersion>, ResolveError> =
        pick(project, version).flatMap { top ->
            if (top.projectId in installed) {
                Outcome.Err(ResolveError.AlreadyInstalled(project))
            } else {
                val walk = Walk(installed)
                walk.visit(top).flatMap { walk.incompatibility() ?: Outcome.Ok(walk.chosen.values.toList()) }
            }
        }

    private suspend fun pick(
        project: String,
        version: String?,
    ): Outcome<ModrinthVersion, ResolveError> {
        val versions =
            when (val listed = modrinth.projectVersions(project, gameVersion)) {
                is Outcome.Ok -> listed.value
                is Outcome.Err -> return Outcome.Err(recordsError(listed.error, project))
            }
        val asked = version?.let { wanted -> versions.firstOrNull { it.versionNumber == wanted || it.id == wanted } }
        return when {
            versions.isEmpty() -> {
                Outcome.Err(ResolveError.NoVersion(project, gameVersion))
            }

            version != null && asked == null -> {
                Outcome.Err(ResolveError.UnknownVersion(project, version))
            }

            asked != null && asked.type != VersionType.RELEASE -> {
                Outcome.Err(ResolveError.Prerelease(project, asked.versionNumber))
            }

            asked != null -> {
                Outcome.Ok(asked)
            }

            else -> {
                versions.firstOrNull { it.type == VersionType.RELEASE }?.let { Outcome.Ok(it) }
                    ?: Outcome.Err(ResolveError.NoRelease(project))
            }
        }
    }

    private fun recordsError(
        error: ModrinthError,
        project: String,
    ): ResolveError = if (error is ModrinthError.UnknownProjects) ResolveError.UnknownProject(project) else ResolveError.Records(error)

    // One resolution: versions chosen so far by project id, in the order they can be loaded.
    private inner class Walk(
        private val installed: Map<String, String>,
    ) {
        val chosen = LinkedHashMap<String, ModrinthVersion>()
        private val visiting = mutableSetOf<String>()

        suspend fun visit(version: ModrinthVersion): Outcome<Unit, ResolveError> {
            visiting += version.projectId
            for (dependency in version.dependencies.filter { it.type == DependencyType.REQUIRED }) {
                val result = require(version, dependency)
                if (result is Outcome.Err) return result
            }
            chosen[version.projectId] = version
            return if (chosen.size > MAX_VERSIONS) Outcome.Err(ResolveError.TooMany(MAX_VERSIONS)) else Outcome.Ok(Unit)
        }

        private suspend fun require(
            dependent: ModrinthVersion,
            dependency: ModrinthDependency,
        ): Outcome<Unit, ResolveError> =
            pinnedVersion(dependency).flatMap { pinned ->
                val projectId = pinned?.projectId ?: dependency.projectId
                if (projectId == null) Outcome.Err(ResolveError.ExternalDependency(dependent.id)) else add(projectId, pinned)
            }

        // Adds [projectId] at [pinned], or at its newest release, unless it is installed or chosen already.
        private suspend fun add(
            projectId: String,
            pinned: ModrinthVersion?,
        ): Outcome<Unit, ResolveError> {
            val present = installed[projectId] ?: chosen[projectId]?.id
            return when {
                present != null && pinned != null && present != pinned.id -> {
                    Outcome.Err(
                        ResolveError.Conflict(projectId, present, pinned.id),
                    )
                }

                present != null || projectId in visiting -> {
                    Outcome.Ok(Unit)
                }

                pinned != null -> {
                    visit(pinned)
                }

                else -> {
                    pick(projectId, null).flatMap { visit(it) }
                }
            }
        }

        // The version a dependency pins, checked to be for this game, or null when it pins none.
        private suspend fun pinnedVersion(dependency: ModrinthDependency): Outcome<ModrinthVersion?, ResolveError> {
            val versionId = dependency.versionId ?: return Outcome.Ok(null)
            return when (val fetched = modrinth.versions(listOf(versionId))) {
                is Outcome.Err -> {
                    Outcome.Err(ResolveError.Records(fetched.error))
                }

                is Outcome.Ok -> {
                    val pinned = fetched.value.getValue(versionId)
                    if (FABRIC in pinned.loaders && gameVersion in pinned.gameVersions) {
                        Outcome.Ok(pinned)
                    } else {
                        Outcome.Err(ResolveError.DependencyNotForGame(versionId))
                    }
                }
            }
        }

        // Incompatibilities are checked once every version is chosen, against those and the installed ones.
        fun incompatibility(): Outcome<List<ModrinthVersion>, ResolveError>? {
            val present = installed.keys + chosen.keys
            return chosen.values.firstNotNullOfOrNull { version ->
                version.dependencies
                    .firstOrNull { it.type == DependencyType.INCOMPATIBLE && it.projectId in present }
                    ?.let { Outcome.Err(ResolveError.Incompatible(version.id, it.projectId.orEmpty())) }
            }
        }
    }

    /** The largest number of versions one add brings. */
    public companion object {
        /** More than this many versions for one mod is refused. */
        public const val MAX_VERSIONS: Int = 64
        private const val FABRIC = "fabric"
    }
}
