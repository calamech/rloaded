package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.download.Http
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.mapError
import io.github.calamech.rloaded.protocol.traverse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
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
    public suspend fun versions(ids: Collection<String>): Outcome<Map<String, ModrinthVersion>, ModrinthError> =
        byIds(ids, VERSIONS) { it.toVersion() }
            .flatMap { found -> complete(ids, found, ModrinthError::UnknownVersions) }

    /**
     * Returns what players see of each project in [ids]: its title, download count and team members,
     * by id, in two requests. Fails with [ModrinthError.UnknownProjects] if Modrinth does not list one.
     */
    public suspend fun projects(ids: Collection<String>): Outcome<Map<String, ModrinthProject>, ModrinthError> =
        byIds(ids, PROJECTS) { Outcome.Ok(it) }
            .flatMap { found -> complete(ids, found, ModrinthError::UnknownProjects) }
            .flatMap { projects ->
                members(projects.values.map { it.team }).map { byTeam ->
                    projects.mapValues { (_, project) ->
                        ModrinthProject(project.id, project.title, project.downloads, byTeam[project.team].orEmpty())
                    }
                }
            }

    // Team members in Modrinth's order, by team id. A team Modrinth does not return has no members.
    private suspend fun members(teams: List<String>): Outcome<Map<String, List<String>>, ModrinthError> {
        val wanted = teams.toSortedSet()
        val invalid = wanted.firstOrNull { !ID.matches(it) }
        if (invalid != null) return Outcome.Err(ModrinthError.InvalidId(invalid))
        return wanted
            .chunked(settings.maxIdsPerRequest)
            .traverse { batch ->
                get("teams", batch).flatMap { decode(it, ListSerializer(ListSerializer(MemberJson.serializer()))) }
            }.map { batches -> batches.flatten().flatten().groupBy({ it.teamId }, { it.user.username }) }
    }

    // A bulk endpoint that answers a list of records for a list of ids.
    private class Endpoint<J>(
        val path: String,
        val singular: String,
        val serializer: KSerializer<List<J>>,
        val idOf: (J) -> String,
    )

    private suspend fun <J, T> byIds(
        ids: Collection<String>,
        endpoint: Endpoint<J>,
        convert: (J) -> Outcome<T, ModrinthError>,
    ): Outcome<Map<String, T>, ModrinthError> {
        val path = endpoint.path
        val wanted = ids.toSortedSet()
        val invalid = wanted.firstOrNull { !ID.matches(it) }
        if (invalid != null) return Outcome.Err(ModrinthError.InvalidId(invalid))
        return wanted
            .chunked(settings.maxIdsPerRequest)
            .traverse { batch ->
                get(path, batch).flatMap { decode(it, endpoint.serializer) }.flatMap { items ->
                    val returned = items.map(endpoint.idOf)
                    when {
                        returned.any { it !in batch } -> {
                            Outcome.Err(
                                ModrinthError.Malformed("$path not asked for: ${returned - batch.toSet()}"),
                            )
                        }

                        returned.toSet().size != returned.size -> {
                            Outcome.Err(ModrinthError.Malformed("a ${endpoint.singular} is listed twice"))
                        }

                        else -> {
                            items.traverse(convert).map { converted -> returned.zip(converted) }
                        }
                    }
                }
            }.map { batches -> batches.flatten().toMap() }
    }

    private suspend fun get(
        path: String,
        batch: List<String>,
    ): Outcome<String, ModrinthError> {
        val ids = batch.joinToString(",", "[", "]") { "\"$it\"" }
        val url = "${settings.apiUrl}/$path?ids=${URLEncoder.encode(ids, StandardCharsets.UTF_8)}"
        return http.text(url, settings.maxResponseBytes).mapError { classify(url, it) }
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9]{1,16}")
        val JSON = Json { ignoreUnknownKeys = true }
        val VERSIONS = Endpoint("versions", "version", ListSerializer(VersionJson.serializer())) { it.id }
        val PROJECTS = Endpoint("projects", "project", ListSerializer(ProjectJson.serializer())) { it.id }
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

        fun <T> decode(
            text: String,
            serializer: KSerializer<T>,
        ): Outcome<T, ModrinthError> =
            try {
                Outcome.Ok(JSON.decodeFromString(serializer, text))
            } catch (e: SerializationException) {
                Outcome.Err(ModrinthError.Malformed(e.message ?: "invalid JSON"))
            } catch (e: IllegalArgumentException) {
                Outcome.Err(ModrinthError.Malformed(e.message ?: "invalid JSON"))
            }

        // The bulk endpoints leave unknown ids out of their answer instead of refusing them.
        fun <T> complete(
            ids: Collection<String>,
            found: Map<String, T>,
            unknown: (List<String>) -> ModrinthError,
        ): Outcome<Map<String, T>, ModrinthError> {
            val missing = ids.toSortedSet().filter { it !in found }
            return if (missing.isEmpty()) Outcome.Ok(found) else Outcome.Err(unknown(missing))
        }
    }
}
