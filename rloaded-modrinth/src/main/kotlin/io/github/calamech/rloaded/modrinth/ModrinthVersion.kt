package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.protocol.Checksum
import java.time.Instant

/** What Modrinth records about one version of a project: the fields Rloaded checks. */
public data class ModrinthVersion(
    val id: String,
    val projectId: String,
    val files: List<ModrinthFile>,
    /** The version as its authors number it, such as `0.165.0+26.3`. */
    val versionNumber: String,
    val type: VersionType,
    val publishedAt: Instant,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val dependencies: List<ModrinthDependency>,
) {
    /** The file Modrinth marks as primary; a version without one has none to install. */
    val primaryFile: ModrinthFile? get() = files.singleOrNull { it.primary }
}

/** One file of a [ModrinthVersion], as Modrinth serves and hashes it. */
public data class ModrinthFile(
    val url: String,
    val fileName: String,
    val size: Long,
    val sha512: Checksum,
    /** Whether this is the version's main file, the mod itself; others are sources and the like. */
    val primary: Boolean,
)

/** The release channel of a version. */
public enum class VersionType {
    /** A stable release. */
    RELEASE,

    /** A beta. */
    BETA,

    /** An alpha. */
    ALPHA,
}

/** A version's dependency on another project, or on one version of it. */
public data class ModrinthDependency(
    /** The project depended on; null when the dependency names only a version or an external file. */
    val projectId: String?,
    /** The exact version depended on, or null for any version. */
    val versionId: String?,
    val type: DependencyType,
)

/** How a version depends on another. */
public enum class DependencyType {
    /** Needed to run. */
    REQUIRED,

    /** Used when present. */
    OPTIONAL,

    /** Breaks when present. */
    INCOMPATIBLE,

    /** Shipped inside the version's own file. */
    EMBEDDED,
}

/** What players see of a project before they install it. [authors] are its team members, in Modrinth's order. */
public data class ModrinthProject(
    val id: String,
    val title: String,
    val downloads: Long,
    val authors: List<String>,
    /** Its categories, featured and additional, such as `optimization`. */
    val categories: List<String>,
)

/** A mod a search found: what players see of it in a list. */
public data class ModrinthHit(
    val projectId: String,
    val slug: String,
    val title: String,
    val author: String,
    val downloads: Long,
)
