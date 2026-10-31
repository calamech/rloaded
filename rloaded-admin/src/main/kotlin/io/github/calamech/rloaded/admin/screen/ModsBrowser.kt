package io.github.calamech.rloaded.admin.screen

import io.github.calamech.rloaded.modrinth.Modrinth
import io.github.calamech.rloaded.modrinth.ModrinthHit
import io.github.calamech.rloaded.protocol.AddProblem
import io.github.calamech.rloaded.protocol.AdminEvent
import io.github.calamech.rloaded.protocol.AdminRequest
import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Where a mod stands on the server. */
public sealed interface InstallStatus {
    /** The server does not have it. */
    public data object Available : InstallStatus

    /** The server is adding it. */
    public data object Installing : InstallStatus

    /** The server has it. */
    public data object Installed : InstallStatus

    /** The server did not add it, for [problem]. */
    public data class Refused(
        val problem: AddProblem,
    ) : InstallStatus
}

/** A mod as the Mods screen lists it. What Modrinth did not say is null. */
public data class ModRow(
    val projectId: String,
    val title: String?,
    val author: String?,
    val downloads: Long?,
    val status: InstallStatus,
)

/** What a search shows. */
public sealed interface SearchView {
    /** Nothing searched yet. */
    public data object Idle : SearchView

    /** Waiting for Modrinth. */
    public data object Searching : SearchView

    /** Modrinth's answer, most relevant first. */
    public data class Found(
        val rows: List<ModRow>,
    ) : SearchView

    /** Modrinth could not be asked. */
    public data object Failed : SearchView
}

/** Everything the Mods screen shows. */
public data class ModsView(
    /** The last query searched, so a screen opened again shows it with its results. */
    val query: String,
    val search: SearchView,
    /** The mods the server has, as its lockfile lists them. */
    val installed: List<ModRow>,
)

/**
 * What the Mods screen shows, and what it asks: searches Modrinth, sends the server requests
 * through [send], and follows the server's answers given to [onEvent]. The server decides whether
 * a mod can be added; this only shows its answers. Each new view goes to [onChange], from any
 * thread; technical details go to [log].
 */
public class ModsBrowser(
    private val modrinth: Modrinth,
    private val gameVersion: String,
    private val send: (AdminRequest) -> Unit,
    private val onChange: (ModsView) -> Unit,
    private val log: (String) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = AtomicReference(State())
    private val requests = AtomicLong()
    private val searching = AtomicReference<Job?>()

    // The facts the view is made of: what the server said, what Modrinth said, what was asked.
    private data class State(
        val serverMods: List<String> = emptyList(),
        val query: String = "",
        val hits: List<ModrinthHit>? = null,
        val searching: Boolean = false,
        val searchFailed: Boolean = false,
        val known: Map<String, Known> = emptyMap(),
        // By project id: the request that asks for it, and where it stands after the server's answer.
        val asked: Map<String, Ask> = emptyMap(),
    )

    private data class Known(
        val title: String,
        val author: String?,
        val downloads: Long,
    )

    private data class Ask(
        val requestId: String,
        val status: InstallStatus,
    )

    /** What the screen shows now. */
    public val view: ModsView get() = render(state.get())

    /** Asks the server for its mods, as the screen opens. */
    public fun open() {
        send(AdminRequest.ListMods)
    }

    /** Searches Modrinth for [query]; a blank query clears the results. */
    public fun search(query: String) {
        searching.getAndSet(null)?.cancel()
        if (query.isBlank()) {
            change { it.copy(query = query, hits = null, searching = false, searchFailed = false) }
            return
        }
        change { it.copy(query = query, searching = true, searchFailed = false) }
        searching.set(
            scope.launch {
                val found = modrinth.search(query, gameVersion, MAX_HITS)
                change { current ->
                    when (found) {
                        is Outcome.Ok -> {
                            current.copy(
                                hits = found.value,
                                searching = false,
                                known = current.known + found.value.associate { it.projectId to known(it) },
                            )
                        }

                        is Outcome.Err -> {
                            log("Searching Modrinth failed: ${found.error}")
                            current.copy(hits = null, searching = false, searchFailed = true)
                        }
                    }
                }
            },
        )
    }

    /** Asks the server to add [projectId]. */
    public fun install(projectId: String) {
        val requestId = "add-${requests.incrementAndGet()}"
        change { it.copy(asked = it.asked + (projectId to Ask(requestId, InstallStatus.Installing))) }
        send(AdminRequest.Add(requestId, projectId, null))
    }

    /** Follows what the server says. */
    public fun onEvent(event: AdminEvent) {
        when (event) {
            is AdminEvent.Access -> {}

            is AdminEvent.Mods -> {
                change { it.copy(serverMods = event.projectIds) }
                scope.launch { describe(event.projectIds) }
            }

            is AdminEvent.Added -> {
                answer(event.requestId, InstallStatus.Installed)
                send(AdminRequest.ListMods)
            }

            is AdminEvent.Refused -> {
                log("The server did not add a mod: ${event.problem}, ${event.detail}")
                answer(event.requestId, InstallStatus.Refused(event.problem))
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    private fun answer(
        requestId: String,
        status: InstallStatus,
    ) = change { current ->
        current.copy(asked = current.asked.mapValues { (_, ask) -> if (ask.requestId == requestId) ask.copy(status = status) else ask })
    }

    // Titles and authors of the server's mods that no search showed yet.
    private suspend fun describe(ids: List<String>) {
        val unknown = ids.filter { it !in state.get().known }
        if (unknown.isEmpty()) return
        when (val projects = modrinth.projects(unknown)) {
            is Outcome.Ok -> {
                val described =
                    projects.value.mapValues { (_, project) ->
                        Known(project.title, project.authors.firstOrNull(), project.downloads)
                    }
                change { it.copy(known = it.known + described) }
            }

            is Outcome.Err -> {
                log("The server's mods could not be described: ${projects.error}")
            }
        }
    }

    private fun known(hit: ModrinthHit) = Known(hit.title, hit.author.ifEmpty { null }, hit.downloads)

    // Under the lock, the views reach the screen in the order the changes happened.
    @Synchronized
    private fun change(transform: (State) -> State) {
        onChange(render(state.updateAndGet(transform)))
    }

    private fun render(state: State): ModsView {
        val installed = state.serverMods.toSet()

        fun row(projectId: String): ModRow {
            val known = state.known[projectId]
            val status = if (projectId in installed) InstallStatus.Installed else state.asked[projectId]?.status ?: InstallStatus.Available
            return ModRow(projectId, known?.title, known?.author, known?.downloads, status)
        }
        val search =
            when {
                state.searching -> SearchView.Searching
                state.searchFailed -> SearchView.Failed
                state.hits == null -> SearchView.Idle
                else -> SearchView.Found(state.hits.map { row(it.projectId) })
            }
        return ModsView(state.query, search, state.serverMods.map { row(it) })
    }

    private companion object {
        const val MAX_HITS = 20
    }
}
