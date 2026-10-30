package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.traverse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Modrinth's version schema, read in its OpenAPI description v2.7.0 on 2026-10-30. Only the fields
// Rloaded checks are declared; Modrinth adds fields over time, so the others are ignored.
@Serializable
internal data class VersionJson(
    val id: String,
    @SerialName("project_id") val projectId: String,
    val files: List<FileJson>,
) {
    fun toVersion(): Outcome<ModrinthVersion, ModrinthError> = files.traverse { it.toFile(id) }.map { ModrinthVersion(id, projectId, it) }
}

@Serializable
internal data class FileJson(
    val url: String,
    val filename: String,
    val size: Long,
    val hashes: HashesJson,
) {
    fun toFile(versionId: String): Outcome<ModrinthFile, ModrinthError> =
        when (val parsed = Checksum.parse(HashAlgorithm.SHA512, hashes.sha512)) {
            is Outcome.Ok -> Outcome.Ok(ModrinthFile(url, filename, size, parsed.value))
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
