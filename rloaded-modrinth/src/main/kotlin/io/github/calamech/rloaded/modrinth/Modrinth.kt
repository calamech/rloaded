package io.github.calamech.rloaded.modrinth

import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.download.Http
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.mapError
import io.github.calamech.rloaded.protocol.traverse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

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
                        ModrinthProject(
                            project.id,
                            project.title,
                            project.downloads,
                            byTeam[project.team].orEmpty(),
                            (project.categories + project.additionalCategories).distinct(),
                        )
                    }
                }
            }

    /**
     * Returns every version of [project], an id or a slug, made for Fabric and [gameVersion], newest
     * first. Fails with [ModrinthError.UnknownProjects] when Modrinth has no such project, and refuses
     * an answer that lists a version of another project or for another loader or game version.
     */
    public suspend fun projectVersions(
        project: String,
        gameVersion: String,
    ): Outcome<List<ModrinthVersion>, ModrinthError> {
        val invalid = listOf(project, gameVersion).firstOrNull { !SLUG.matches(it) }
        if (invalid != null) return Outcome.Err(ModrinthError.InvalidId(invalid))
        val filters = "loaders=${encode("[\"$FABRIC\"]")}&game_versions=${encode("[\"$gameVersion\"]")}&include_changelog=false"
        val url = "${settings.apiUrl}/project/$project/version?$filters"
        return http
            .text(url, settings.maxResponseBytes)
            .mapError { error ->
                if (error is DownloadError.HttpStatus && error.status == NOT_FOUND) {
                    ModrinthError.UnknownProjects(listOf(project))
                } else {
                    classify(url, error)
                }
            }.flatMap { decode(it, ListSerializer(VersionJson.serializer())) }
            .flatMap { versions -> versions.traverse { it.toVersion() } }
            .flatMap { versions ->
                val stray = versions.firstOrNull { FABRIC !in it.loaders || gameVersion !in it.gameVersions }
                val projects = versions.map { it.projectId }.toSet()
                when {
                    stray != null -> Outcome.Err(ModrinthError.Malformed("version ${stray.id} is not for Fabric $gameVersion"))
                    projects.size > 1 -> Outcome.Err(ModrinthError.Malformed("versions of several projects: ${projects.sorted()}"))
                    else -> Outcome.Ok(versions.sortedByDescending { it.publishedAt })
                }
            }
    }

    /**
     * Searches Modrinth's Fabric mods for [gameVersion] matching [query], most relevant first, and
     * returns at most [limit] of them. A query is 1 to [MAX_QUERY] characters; [limit] is 1 to
     * [MAX_HITS].
     */
    public suspend fun search(
        query: String,
        gameVersion: String,
        limit: Int,
    ): Outcome<List<ModrinthHit>, ModrinthError> {
        require(limit in 1..MAX_HITS) { "limit must be 1 to $MAX_HITS" }
        val refused =
            when {
                query.isBlank() || query.length > MAX_QUERY -> ModrinthError.InvalidQuery(query)
                !SLUG.matches(gameVersion) -> ModrinthError.InvalidId(gameVersion)
                else -> null
            }
        if (refused != null) return Outcome.Err(refused)
        val facets = """[["categories:$FABRIC"],["versions:$gameVersion"],["project_type:mod"]]"""
        val url = "${settings.apiUrl}/search?query=${encode(query.trim())}&limit=$limit&facets=${encode(facets)}"
        return http
            .text(url, settings.maxResponseBytes)
            .mapError { classify(url, it) }
            .flatMap { decode(it, SearchJson.serializer()) }
            .flatMap { found ->
                val invalid = found.hits.firstOrNull { !ID.matches(it.projectId) }
                when {
                    invalid != null -> Outcome.Err(ModrinthError.Malformed("a search hit has the project id ${invalid.projectId}"))
                    found.hits.size > limit -> Outcome.Err(ModrinthError.Malformed("${found.hits.size} search hits for $limit asked"))
                    else -> Outcome.Ok(found.hits.map { ModrinthHit(it.projectId, it.slug, it.title, it.author, it.downloads) })
                }
            }
    }

    /** [projects] for Java callers: runs on the IO dispatcher and completes with the outcome. */
    public fun projectsAsync(ids: Collection<String>): CompletableFuture<Outcome<Map<String, ModrinthProject>, ModrinthError>> =
        CoroutineScope(Dispatchers.IO).future { projects(ids) }

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
        val url = "${settings.apiUrl}/$path?ids=${encode(ids)}"
        return http.text(url, settings.maxResponseBytes).mapError { classify(url, it) }
    }

    /** Bounds of a search. */
    public companion object {
        /** The longest query a search sends. */
        public const val MAX_QUERY: Int = 100

        /** The most hits a search asks for, Modrinth's own limit. */
        public const val MAX_HITS: Int = 100

        private val ID = Regex("[A-Za-z0-9]{1,16}")

        // Project slugs and game versions, kept to characters that need no care in a URL path.
        private val SLUG = Regex("[A-Za-z0-9_.+-]{1,64}")
        private const val FABRIC = "fabric"
        private const val NOT_FOUND = 404

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        private val JSON = Json { ignoreUnknownKeys = true }
        private val VERSIONS = Endpoint("versions", "version", ListSerializer(VersionJson.serializer())) { it.id }
        private val PROJECTS = Endpoint("projects", "project", ListSerializer(ProjectJson.serializer())) { it.id }
        private const val TOO_MANY_REQUESTS = 429

        private fun classify(
            url: String,
            error: DownloadError,
        ): ModrinthError =
            if (error is DownloadError.HttpStatus && error.status == TOO_MANY_REQUESTS) {
                ModrinthError.RateLimited(url)
            } else {
                ModrinthError.Download(error)
            }

        private fun <T> decode(
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
        private fun <T> complete(
            ids: Collection<String>,
            found: Map<String, T>,
            unknown: (List<String>) -> ModrinthError,
        ): Outcome<Map<String, T>, ModrinthError> {
            val missing = ids.toSortedSet().filter { it !in found }
            return if (missing.isEmpty()) Outcome.Ok(found) else Outcome.Err(unknown(missing))
        }
    }
}
