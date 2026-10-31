package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.core.download.DownloadError

/** Why Modrinth's records could not be obtained. */
public sealed interface ModrinthError {
    /** An id is not a Modrinth id; nothing was requested. */
    public data class InvalidId(
        val id: String,
    ) : ModrinthError

    /** A search query is empty or too long; nothing was requested. */
    public data class InvalidQuery(
        val query: String,
    ) : ModrinthError

    /** Modrinth refused the request because this address sent too many; it allows 300 a minute. */
    public data class RateLimited(
        val url: String,
    ) : ModrinthError

    /** The request failed for another reason. */
    public data class Download(
        val error: DownloadError,
    ) : ModrinthError

    /** The answer does not have the expected shape, or lists versions that were not asked for. */
    public data class Malformed(
        val reason: String,
    ) : ModrinthError

    /** Modrinth has no version with these ids, or no longer lists them. */
    public data class UnknownVersions(
        val ids: List<String>,
    ) : ModrinthError

    /** Modrinth has no project with these ids, or no longer lists them. */
    public data class UnknownProjects(
        val ids: List<String>,
    ) : ModrinthError
}
