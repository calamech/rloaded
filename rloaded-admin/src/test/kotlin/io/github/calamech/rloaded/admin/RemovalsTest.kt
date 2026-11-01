package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.core.admission.JarClassifier
import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.RetryPolicy
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.core.download.respond
import io.github.calamech.rloaded.core.lockfile.BaseSetEntry
import io.github.calamech.rloaded.core.lockfile.LockedMod
import io.github.calamech.rloaded.core.lockfile.Lockfile
import io.github.calamech.rloaded.modrinth.Modrinth
import io.github.calamech.rloaded.modrinth.ModrinthSettings
import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.ModEntry
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.RemovalProblem
import io.github.calamech.rloaded.protocol.Side
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals

class RemovalsTest {
    @TempDir
    lateinit var directory: Path

    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())
    private val now = Instant.parse("2026-11-01T10:00:00Z")
    private val lockfile get() = directory.resolve("rloaded/lockfile.json")
    private val audit get() = directory.resolve("rloaded/audit.log")
    private val base = BaseSetEntry("P7dR8mSH", "EQKgpeKu", sha("fabric api"))

    // menu needs library; standalone needs nothing.
    private val mods = listOf(mod("library"), mod("menu"), mod("standalone"))
    private val dependencies = mapOf("vmenu" to "library")

    @BeforeEach
    fun write() {
        ok(Lockfile("26.3", "0.1.0", listOf(base), mods.map { LockedMod(it, now, "console") }, emptyList()).write(lockfile))
        server.route("/v2/versions") { exchange, _ ->
            val versions =
                ids(exchange.requestURI.rawQuery).joinToString(",", "[", "]") { id -> version(id.removePrefix("v"), dependencies[id]) }
            exchange.respond(200, versions.toByteArray())
        }
        server.route("/v2/projects") { exchange, _ ->
            val projects =
                ids(exchange.requestURI.rawQuery).joinToString(",", "[", "]") { id ->
                    """{"id": "$id", "title": "${id.replaceFirstChar { it.uppercase() }}", "downloads": 5000, "team": "t$id", """ +
                        """"categories": [], "additional_categories": []}"""
                }
            exchange.respond(200, projects.toByteArray())
        }
        server.route("/v2/teams") { exchange, _ -> exchange.respond(200, "[]".toByteArray()) }
        server.route("/v2/project/standalone-slug/version") { exchange, _ ->
            exchange.respond(200, "[${version("standalone", null)}]".toByteArray())
        }
        server.route("/v2/project/unknown/version") { exchange, _ -> exchange.respond(404, ByteArray(0)) }
    }

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `marks a mod nothing needs, by id or slug, and records it`() {
        assertEquals(Outcome.Ok("standalone"), remove("standalone-slug"))

        assertEquals(listOf("standalone"), ok(Lockfile.read(lockfile)).pendingRemovals)
        assertEquals(listOf("requested remove standalone-slug", "marked"), trail())
    }

    @Test
    fun `refuses a mod that a staying mod needs, naming it`() {
        val refusal = (remove("library") as Outcome.Err).error

        assertEquals(RemovalRefusal.Needed("library", listOf("Menu")), refusal)
        assertEquals("Menu needs it", refusal.reason)
        assertEquals(emptyList(), ok(Lockfile.read(lockfile)).pendingRemovals)
        assertEquals(listOf("requested remove library", "refused NEEDED"), trail())
    }

    @Test
    fun `lets a needed mod go once the mods needing it are marked too`() {
        assertEquals(Outcome.Ok("menu"), remove("menu"))
        assertEquals(Outcome.Ok("library"), remove("library"))

        assertEquals(listOf("menu", "library"), ok(Lockfile.read(lockfile)).pendingRemovals)
    }

    @Test
    fun `keeps a marked mod, and refuses what it cannot do`() {
        remove("standalone")

        assertEquals(Outcome.Err(RemovalRefusal.AlreadyMarked("standalone")), remove("standalone"))
        assertEquals(Outcome.Ok("standalone"), keep("standalone"))
        assertEquals(Outcome.Err(RemovalRefusal.NotMarked("standalone")), keep("standalone"))
        assertEquals(Outcome.Err(RemovalRefusal.BaseSet("P7dR8mSH")), remove("P7dR8mSH"))
        assertEquals(Outcome.Err(RemovalRefusal.NotInstalled("unknown")), remove("unknown"))
        assertEquals(emptyList(), ok(Lockfile.read(lockfile)).pendingRemovals)
        assertEquals(RemovalProblem.NOT_INSTALLED, RemovalRefusal.NotInstalled("unknown").problem)
    }

    @Test
    fun `changes nothing it cannot record in the audit log`() {
        Files.createDirectories(audit)

        assertEquals(RemovalProblem.STORAGE, (remove("standalone") as Outcome.Err).error.problem)
        assertEquals(emptyList(), ok(Lockfile.read(lockfile)).pendingRemovals)
    }

    private fun remove(project: String) = runBlocking { service.removals.remove(project, AddActor.Console) }

    private fun keep(project: String) = runBlocking { service.removals.keep(project, AddActor.Console) }

    // Each line of the audit log in short.
    private fun trail(): List<String> =
        Files.readAllLines(audit).map { Json.parseToJsonElement(it).jsonObject }.map { line ->
            val event = line.getValue("event").jsonPrimitive.content
            when (event) {
                "requested" -> "requested ${line.getValue(
                    "action",
                ).jsonPrimitive.content} ${line.getValue("project").jsonPrimitive.content}"

                "refused" -> "refused ${line.getValue("problem").jsonPrimitive.content}"

                else -> event
            }
        }

    private val service by lazy {
        val modrinth = Modrinth(http, ModrinthSettings(apiUrl = server.url("/v2")))
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        AdmissionService(
            Resolver(modrinth, "26.3"),
            RecordChecks(
                modrinth,
                ReputationRules(Duration.ofHours(72), 1_000),
                { CompatDatabase(emptyMap(), emptyMap(), emptyMap()) },
                clock,
            ),
            ArtifactStore(directory.resolve("store"), http, RetryPolicy(maxAttempts = 1)),
            JarRules(JarClassifier(emptySet(), setOf("default")), { null }, { _, _ -> Outcome.Ok(Unit) }),
            AddTarget(
                LockfileLocation(lockfile, Lockfile("26.3", "0.1.0", listOf(base), emptyList(), emptyList())),
                { CompletableFuture.completedFuture(Outcome.Ok(Unit)) },
                LiveGame.DEDICATED_SERVER,
                AuditLog(audit, clock) { throw AssertionError("an outcome was not recorded: $it") },
            ),
            clock,
        )
    }

    private fun ids(query: String): List<String> =
        Regex("\"([A-Za-z0-9]+)\"").findAll(URLDecoder.decode(query, Charsets.UTF_8)).map { it.groupValues[1] }.toList()

    private fun version(
        project: String,
        dependsOn: String?,
    ): String {
        val dependency = dependsOn?.let { """{"project_id": "$it", "dependency_type": "required"}""" }.orEmpty()
        return """{"id": "v$project", "project_id": "$project", "version_number": "1.0.0", "version_type": "release", """ +
            """"date_published": "2026-10-01T00:00:00Z", "game_versions": ["26.3"], "loaders": ["fabric"], """ +
            """"dependencies": [$dependency], "files": [{"url": "${server.url("/cdn/$project.jar")}", "filename": "$project.jar", """ +
            """"size": 10, "primary": true, "hashes": {"sha512": "${"a".repeat(128)}"}}]}"""
    }

    private fun mod(project: String) = ModEntry(project, "v$project", "$project.jar", sha(project), 10, Side.BOTH)

    private fun sha(text: String): Checksum =
        ok(
            Checksum.parse(
                HashAlgorithm.SHA512,
                java.security.MessageDigest
                    .getInstance("SHA-512")
                    .digest(text.toByteArray())
                    .toHexString(),
            ),
        )

    private fun <T, E> ok(outcome: Outcome<T, E>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected a value, got ${outcome.error}")
        }
}
