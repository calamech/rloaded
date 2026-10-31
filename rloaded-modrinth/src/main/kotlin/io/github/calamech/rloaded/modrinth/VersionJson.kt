package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.traverse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeParseException

// Modrinth's version schema, read in its OpenAPI description v2.7.0 on 2026-10-30 and 2026-10-31.
// Only the fields Rloaded checks are declared; Modrinth adds fields over time, so the others are
// ignored. A value outside the enums it documents makes the record malformed.
@Serializable
internal data class VersionJson(
    val id: String,
    @SerialName("project_id") val projectId: String,
    val files: List<FileJson>,
    @SerialName("version_number") val versionNumber: String,
    @SerialName("version_type") val versionType: VersionTypeJson,
    @SerialName("date_published") val datePublished: String,
    @SerialName("game_versions") val gameVersions: List<String>,
    val loaders: List<String>,
    val dependencies: List<DependencyJson>,
) {
    fun toVersion(): Outcome<ModrinthVersion, ModrinthError> {
        val published =
            try {
                Instant.parse(datePublished)
            } catch (_: DateTimeParseException) {
                return Outcome.Err(ModrinthError.Malformed("version $id: date_published $datePublished is not an instant"))
            }
        return files.traverse { it.toFile(id) }.map { files ->
            ModrinthVersion(
                id,
                projectId,
                files,
                versionNumber,
                versionType.type,
                published,
                gameVersions,
                loaders,
                dependencies.map { ModrinthDependency(it.projectId, it.versionId, it.dependencyType.type) },
            )
        }
    }
}

@Serializable
internal enum class VersionTypeJson(
    val type: VersionType,
) {
    @SerialName("release")
    RELEASE(VersionType.RELEASE),

    @SerialName("beta")
    BETA(VersionType.BETA),

    @SerialName("alpha")
    ALPHA(VersionType.ALPHA),
}

@Serializable
internal data class DependencyJson(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("version_id") val versionId: String? = null,
    @SerialName("dependency_type") val dependencyType: DependencyTypeJson,
)

@Serializable
internal enum class DependencyTypeJson(
    val type: DependencyType,
) {
    @SerialName("required")
    REQUIRED(DependencyType.REQUIRED),

    @SerialName("optional")
    OPTIONAL(DependencyType.OPTIONAL),

    @SerialName("incompatible")
    INCOMPATIBLE(DependencyType.INCOMPATIBLE),

    @SerialName("embedded")
    EMBEDDED(DependencyType.EMBEDDED),
}

@Serializable
internal data class FileJson(
    val url: String,
    val filename: String,
    val size: Long,
    val hashes: HashesJson,
    val primary: Boolean,
) {
    fun toFile(versionId: String): Outcome<ModrinthFile, ModrinthError> =
        when (val parsed = Checksum.parse(HashAlgorithm.SHA512, hashes.sha512)) {
            is Outcome.Ok -> Outcome.Ok(ModrinthFile(url, filename, size, parsed.value, primary))
            is Outcome.Err -> Outcome.Err(ModrinthError.Malformed("version $versionId: $filename has no valid SHA-512"))
        }
}

@Serializable
internal data class HashesJson(
    val sha512: String,
)

@Serializable
internal data class ProjectJson(
    val id: String,
    val title: String,
    val downloads: Long,
    val team: String,
    val categories: List<String>,
    @SerialName("additional_categories") val additionalCategories: List<String>,
)

@Serializable
internal data class MemberJson(
    @SerialName("team_id") val teamId: String,
    val user: UserJson,
)

@Serializable
internal data class UserJson(
    val username: String,
)

@Serializable
internal data class SearchJson(
    val hits: List<HitJson>,
)

@Serializable
internal data class HitJson(
    @SerialName("project_id") val projectId: String,
    val slug: String,
    val title: String,
    val author: String,
    val downloads: Long,
)
