package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.protocol.Checksum

/** What Modrinth records about one version of a project: the fields Rloaded checks. */
public data class ModrinthVersion(
    val id: String,
    val projectId: String,
    val files: List<ModrinthFile>,
)

/** One file of a [ModrinthVersion], as Modrinth serves and hashes it. */
public data class ModrinthFile(
    val url: String,
    val fileName: String,
    val size: Long,
    val sha512: Checksum,
)
