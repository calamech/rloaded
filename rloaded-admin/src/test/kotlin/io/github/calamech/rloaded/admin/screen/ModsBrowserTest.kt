package io.github.calamech.rloaded.admin.screen

import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.core.download.respond
import io.github.calamech.rloaded.modrinth.Modrinth
import io.github.calamech.rloaded.modrinth.ModrinthSettings
import io.github.calamech.rloaded.modrinth.SearchRecordings
import io.github.calamech.rloaded.protocol.AddProblem
import io.github.calamech.rloaded.protocol.AdminEvent
import io.github.calamech.rloaded.protocol.AdminRequest
import io.github.calamech.rloaded.protocol.RemovalProblem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals

class ModsBrowserTest {
    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())
    private val sent = CopyOnWriteArrayList<AdminRequest>()
    private val logged = CopyOnWriteArrayList<String>()
    private val views = CopyOnWriteArrayList<ModsView>()
    private val browser =
        ModsBrowser(Modrinth(http, ModrinthSettings(apiUrl = server.url("/v2"))), "26.3", { sent += it }, { views += it }) {
            logged += it
        }

    @AfterEach
    fun close() {
        browser.close()
        http.close()
        server.close()
    }

    @Test
    fun `shows the server's mods, described by Modrinth, once it asked for them`() {
        describeProjects()

        browser.open()
        browser.onEvent(AdminEvent.Mods(listOf("P7dR8mSH"), emptyList()))

        assertEquals(listOf<AdminRequest>(AdminRequest.ListMods), sent)
        val view = awaitView { it.installed.firstOrNull()?.title != null }
        assertEquals(listOf(ModRow("P7dR8mSH", "Fabric API", "modmuss50", 900, InstallStatus.Installed)), view.installed)
    }

    @Test
    fun `searches Modrinth, marks the mods the server has, and clears on a blank query`() {
        serveSearch()
        browser.onEvent(AdminEvent.Mods(listOf("AANobbMI"), emptyList()))

        browser.search("sodium")

        val rows = (awaitView { it.search is SearchView.Found }.search as SearchView.Found).rows
        assertEquals(ModRow("AANobbMI", "Sodium", "jellysquid3", 257_628_792, InstallStatus.Installed), rows.first())
        assertEquals(listOf(InstallStatus.Available, InstallStatus.Available), rows.drop(1).map { it.status })
        assertEquals("sodium", browser.view.query)
        browser.search(" ")
        assertEquals(SearchView.Idle, browser.view.search)
    }

    @Test
    fun `asks the server to add a mod, shows its answer on the mod's row, and asks for the mods again after an add`() {
        serveSearch()
        browser.search("sodium")
        awaitView { it.search is SearchView.Found }

        browser.install("PtjYWJkn")
        browser.install("Bh37bMuy")

        assertEquals(InstallStatus.Installing, statusOf("PtjYWJkn"))
        val (first, second) = sent.filterIsInstance<AdminRequest.Add>()
        assertEquals(listOf("PtjYWJkn", "Bh37bMuy"), listOf(first.projectId, second.projectId))
        assertEquals(true, browser.answers(AdminEvent.Added(first.requestId, emptyList())))
        assertEquals(false, browser.answers(AdminEvent.Refused("someone-else", AddProblem.NOT_ALLOWED, "not ours")))
        assertEquals(false, browser.answers(AdminEvent.Mods(emptyList(), emptyList())))
        browser.onEvent(AdminEvent.Refused(second.requestId, AddProblem.BUSY, "another add is running"))
        browser.onEvent(AdminEvent.Added(first.requestId, listOf("sodium-extra.jar")))
        assertEquals(InstallStatus.Refused(AddProblem.BUSY), statusOf("Bh37bMuy"))
        assertEquals(InstallStatus.Installed, statusOf("PtjYWJkn"))
        assertEquals(AdminRequest.ListMods, sent.last())
    }

    @Test
    fun `marks a mod for removal, keeps it, and shows what the server refused`() {
        describeProjects()
        browser.onEvent(AdminEvent.Access(allowed = true, removals = true))
        browser.onEvent(AdminEvent.Mods(listOf("P7dR8mSH", "AANobbMI"), emptyList()))

        browser.remove("AANobbMI")
        browser.remove("P7dR8mSH")

        assertEquals(true, browser.view.removals)
        assertEquals(listOf(InstallStatus.Changing, InstallStatus.Changing), browser.view.installed.map { it.status })
        val (sodium, fabricApi) = sent.filterIsInstance<AdminRequest.Remove>()
        assertEquals(true, browser.answers(AdminEvent.Changed(sodium.requestId)))
        browser.onEvent(AdminEvent.RemovalRefused(fabricApi.requestId, RemovalProblem.BASE_SET, emptyList(), "part of the base set"))
        browser.onEvent(AdminEvent.Changed(sodium.requestId))
        assertEquals(AdminRequest.ListMods, sent.last())
        browser.onEvent(AdminEvent.Mods(listOf("P7dR8mSH", "AANobbMI"), listOf("AANobbMI")))
        assertEquals(
            listOf(InstallStatus.NotChanged(RemovalProblem.BASE_SET, emptyList()), InstallStatus.RemovedAtRestart),
            browser.view.installed.map { it.status },
        )

        browser.keep("AANobbMI")
        val keep = sent.filterIsInstance<AdminRequest.Keep>().single()
        assertEquals("AANobbMI", keep.projectId)
        browser.onEvent(AdminEvent.Changed(keep.requestId))
        browser.onEvent(AdminEvent.Mods(listOf("P7dR8mSH", "AANobbMI"), emptyList()))
        assertEquals(
            InstallStatus.Installed,
            browser.view.installed
                .last()
                .status,
        )
    }

    @Test
    fun `says when Modrinth cannot be searched`() {
        server.route("/v2/search") { exchange, _ -> exchange.respond(500, ByteArray(0)) }

        browser.search("sodium")

        assertEquals(SearchView.Failed, awaitView { it.search == SearchView.Failed }.search)
        assertEquals(1, logged.count { "Searching Modrinth failed" in it })
    }

    private fun serveSearch() =
        server.route("/v2/search") { exchange, _ -> exchange.respond(200, SearchRecordings.of("sodium").toByteArray()) }

    private fun describeProjects() {
        server.route("/v2/projects") { exchange, _ ->
            val body =
                """[{"id": "P7dR8mSH", "title": "Fabric API", "downloads": 900, "team": "BZoBsPo6", """ +
                    """"categories": ["library"], "additional_categories": []}]"""
            exchange.respond(200, body.toByteArray())
        }
        server.route("/v2/teams") { exchange, _ ->
            exchange.respond(200, """[[{"team_id": "BZoBsPo6", "user": {"username": "modmuss50"}}]]""".toByteArray())
        }
    }

    private fun statusOf(projectId: String): InstallStatus? =
        (browser.view.search as? SearchView.Found)?.rows?.find { it.projectId == projectId }?.status

    // Waits for the view the screen gets: the browser changes its state first, then calls the screen.
    private fun awaitView(condition: (ModsView) -> Boolean): ModsView {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (views.lastOrNull()?.let(condition) != true) {
            check(System.nanoTime() < deadline) { "the screen never got there: ${views.lastOrNull()}; log: $logged" }
            Thread.sleep(10)
        }
        assertEquals(browser.view, views.last(), "the screen got the last view")
        return browser.view
    }
}
