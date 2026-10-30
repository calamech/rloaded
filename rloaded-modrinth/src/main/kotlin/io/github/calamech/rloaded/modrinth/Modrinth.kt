package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.download.Http
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.mapError
import io.github.calamech.rloaded.protocol.traverse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Where Modrinth's API is, and the limits applied to its answers. */
public data class ModrinthSettings(
    /** The base URL of version 2 of the API. */
    val apiUrl: String = "https://api.modrinth.com/v2",
    /** Versions asked for in one request; larger sets are split. */
    val maxIdsPerRequest: Int = 100,
    /** Largest answer accepted for one request; changelogs make versions large. */
    val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
)

private const val DEFAULT_MAX_RESPONSE_BYTES = 16L * 1024 * 1024

/**
 * Reads Modrinth's records of mod versions. Requests go through [http], which applies the URL
 * policy, the timeouts and the User-Agent Modrinth requires; nothing is retried here.
 */
public class Modrinth(
    private val http: Http,
    private val settings: ModrinthSettings = ModrinthSettings(),
) {
    /**
     * Returns Modrinth's record of every version in [ids], by id. Fails with
     * [ModrinthError.UnknownVersions] if Modrinth does not list one of them: it leaves unknown ids
     * out of its answer instead of refusing them.
     */
    public suspend fun versions(ids: Collection<String>): Outcome<Map<String, ModrinthVersion>, ModrinthError> {
        val wanted = ids.toSortedSet()
        val invalid = wanted.firstOrNull { !ID.matches(it) }
        if (invalid != null) return Outcome.Err(ModrinthError.InvalidId(invalid))
        return wanted.chunked(settings.maxIdsPerRequest).traverse { fetch(it) }.flatMap { batches ->
            val found = batches.flatten().associateBy { it.id }
            val missing = wanted.filter { it !in found }
            if (missing.isEmpty()) Outcome.Ok(found) else Outcome.Err(ModrinthError.UnknownVersions(missing))
        }
    }

    private suspend fun fetch(batch: List<String>): Outcome<List<ModrinthVersion>, ModrinthError> {
        val ids = batch.joinToString(",", "[", "]") { "\"$it\"" }
        val url = "${settings.apiUrl}/versions?ids=${URLEncoder.encode(ids, StandardCharsets.UTF_8)}"
        return http
            .text(url, settings.maxResponseBytes)
            .mapError { classify(url, it) }
            .flatMap { decode(it) }
            .flatMap { versions ->
                val returned = versions.map { it.id }
                when {
                    returned.any { it !in batch } -> {
                        Outcome.Err(
                            ModrinthError.Malformed("versions not asked for: ${returned - batch.toSet()}"),
                        )
                    }

                    returned.toSet().size != returned.size -> {
                        Outcome.Err(ModrinthError.Malformed("a version is listed twice"))
                    }

                    else -> {
                        versions.traverse { it.toVersion() }
                    }
                }
            }
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9]{1,16}")
        val JSON = Json { ignoreUnknownKeys = true }
        const val TOO_MANY_REQUESTS = 429

        fun classify(
            url: String,
            error: DownloadError,
        ): ModrinthError =
            if (error is DownloadError.HttpStatus && error.status == TOO_MANY_REQUESTS) {
                ModrinthError.RateLimited(url)
            } else {
                ModrinthError.Download(error)
            }

        fun decode(text: String): Outcome<List<VersionJson>, ModrinthError> =
            try {
                Outcome.Ok(JSON.decodeFromString<List<VersionJson>>(text))
            } catch (e: SerializationException) {
                Outcome.Err(ModrinthError.Malformed(e.message ?: "invalid JSON"))
            } catch (e: IllegalArgumentException) {
                Outcome.Err(ModrinthError.Malformed(e.message ?: "invalid JSON"))
            }
    }
}
