package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.admin.staging.StagingFailure
import io.github.calamech.rloaded.admin.staging.StagingReport
import io.github.calamech.rloaded.core.admission.JarClassifier
import io.github.calamech.rloaded.core.admission.Refusal
import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.RetryPolicy
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.core.download.respond
import io.github.calamech.rloaded.core.lockfile.BaseSetEntry
import io.github.calamech.rloaded.core.lockfile.Lockfile
import io.github.calamech.rloaded.core.lockfile.LockfileError
import io.github.calamech.rloaded.modrinth.Modrinth
import io.github.calamech.rloaded.modrinth.ModrinthSettings
import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.Side
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AdmissionServiceTest {
    @TempDir
    lateinit var directory: Path

    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())
    private val now = Instant.parse("2026-10-31T10:00:00Z")
    private val serverChecks = mutableListOf<List<String>>()
    private var serverRefusal: String? = null
    private val staged = mutableListOf<List<String>>()
    private val stagedWith = mutableListOf<List<String>>()
    private var stagingFailure: StagingFailure? = null
    private val applied = mutableListOf<List<String>>()
    private var applyFailure: String? = null
    private var whileApplying: () -> Unit = {}
    private var whileChecking: () -> Unit = {}
    private var game = LiveGame.DEDICATED_SERVER
    private val lockfile get() = directory.resolve("rloaded/lockfile.json")
    private val base = BaseSetEntry("P7dR8mSH", "EQKgpeKu", sha("fabric api"))
    private val empty = Lockfile("26.3", "0.1.0", listOf(base), emptyList(), emptyList())

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `writes the mod and its dependencies to the lockfile, with the side each jar declares`() {
        publish("library", jar("library", environment = "*"))
        publish("menu", jar("menu", environment = "client"), dependsOn = "library")

        val added = ok(add("menu"))

        assertEquals(listOf("library" to Side.BOTH, "menu" to Side.CLIENT), added.map { it.entry.projectId to it.entry.side })
        assertEquals(listOf("1.0.0", "1.0.0"), added.map { it.versionNumber })
        val written = ok(Lockfile.read(lockfile))
        assertEquals(listOf(base), written.baseSet)
        assertEquals(added.map { it.entry }, written.mods.map { it.entry })
        assertEquals(listOf(now, now), written.mods.map { it.addedAt })
        assertEquals(listOf("console", "console"), written.mods.map { it.addedBy })
        assertEquals(listOf(listOf("library")), serverChecks, "the server checks what it loads, not client-only mods")
        assertEquals(listOf(listOf("library")), staged, "the staging server tries what the server loads, not client-only mods")
        assertEquals(listOf(listOf("library")), applied, "the running server loads what it runs, not client-only mods")
    }

    @Test
    fun `checks and loads mods for the client too in a singleplayer world, where the game is the client`() {
        game = LiveGame.SINGLEPLAYER
        publish("library", jar("library", environment = "*"))
        publish("menu", jar("menu", environment = "client"), dependsOn = "library")

        val added = ok(add("menu"))

        assertEquals(listOf("library" to Side.BOTH, "menu" to Side.CLIENT), added.map { it.entry.projectId to it.entry.side })
        assertEquals(listOf(listOf("library", "menu")), serverChecks)
        assertEquals(listOf(listOf("library", "menu")), applied)
        assertEquals(listOf("library", "menu"), ok(Lockfile.read(lockfile)).mods.map { it.entry.projectId })
    }

    @Test
    fun `writes nothing when the running server cannot load the mods`() {
        publish("unloadable", jar("unloadable"))
        applyFailure = "a mod with id unloadable is already loaded"

        assertEquals(Outcome.Err(AddRefusal.NotApplied("a mod with id unloadable is already loaded")), add("unloadable"))
        assertFalse(lockfile.exists())
    }

    @Test
    fun `says the mods run but are not recorded when the lockfile cannot be written after they loaded`() {
        publish("loaded", jar("loaded"))
        whileApplying = { lockfile.toFile().mkdirs() }

        val refusal = (add("loaded") as Outcome.Err).error

        assertIs<AddRefusal.NotRecorded>(refusal)
        assertEquals(listOf(listOf("loaded")), applied)
    }

    @Test
    fun `writes nothing when the mods fail on the staging server, and says why`() {
        publish("crashing", jar("crashing"))
        val log = directory.resolve("staging/logs/latest.log")
        stagingFailure =
            StagingFailure.Failed(StagingReport.of(listOf("crashing")).failing("loading the mods threw: boom"), log)

        val refusal = (add("crashing") as Outcome.Err).error

        assertEquals(AddRefusal.StagingRefused(stagingFailure as StagingFailure), refusal)
        assertEquals("it failed on the staging server: loading the mods threw: boom; see $log", refusal.reason)
        assertEquals(listOf(listOf("crashing")), staged)
        assertEquals(emptyList(), applied, "the running server never loads what failed on the staging server")
        assertFalse(lockfile.exists())
    }

    @Test
    fun `does not start a staging server for mods only players load`() {
        publish("menu", jar("menu", environment = "client"))
        stagingFailure = StagingFailure.NotStarted("it must not start")

        assertEquals(listOf("menu"), ok(add("menu")).map { it.entry.projectId })
        assertEquals(emptyList(), staged)
        assertEquals(listOf(emptyList()), applied, "players load it; the server loads nothing")
    }

    @Test
    fun `keeps the mods already in the lockfile and does not add them again`() {
        publish("library", jar("library"))
        publish("menu", jar("menu"), dependsOn = "library")
        ok(add("library"))

        val added = ok(add("menu"))

        assertEquals(listOf("menu"), added.map { it.entry.projectId })
        assertEquals(listOf("library", "menu"), ok(Lockfile.read(lockfile)).mods.map { it.entry.projectId })
        assertEquals(listOf(emptyList(), listOf("library")), stagedWith, "the staging server runs the mods added before")
    }

    @Test
    fun `writes nothing when a jar breaks a rule, when the server refuses, or when a file is missing`() {
        publish("early", jar("early", entrypoints = """"entrypoints": {"preLaunch": ["a.Early"]},"""))
        assertEquals(Outcome.Err(AddRefusal.Refused(listOf(Refusal.PreLaunch("early")))), add("early"))

        publish("needy", jar("needy"))
        serverRefusal = "needy.jar is refused: it depends on missing-mod"
        assertEquals(Outcome.Err(AddRefusal.ServerRefused("needy.jar is refused: it depends on missing-mod")), add("needy"))
        serverRefusal = null
        assertEquals(emptyList(), staged, "refused jars never reach the staging server")

        publish("gone", jar("gone"))
        server.serve("/cdn/gone.jar", ByteArray(0), status = 404)
        assertIs<AddRefusal.NotDownloaded>((add("gone") as Outcome.Err).error)

        server.serve("/v2/project/unknown/version", ByteArray(0), status = 404)
        assertEquals(Outcome.Err(AddRefusal.Unresolved(ResolveError.UnknownProject("unknown"))), add("unknown"))

        assertFalse(lockfile.exists())
    }

    @Test
    fun `refuses versions published too recently and projects with too few downloads, listing every one`() {
        publish("fresh", jar("fresh"), published = "2026-10-30T12:00:00Z", downloadCount = 10)
        publish("needsfresh", jar("needsfresh"), dependsOn = "fresh")

        val refusal = (add("needsfresh") as Outcome.Err).error

        assertEquals(
            AddRefusal.Unsuitable(
                listOf(
                    RecordFailure.TooRecent("fresh", "1.0.0", Instant.parse("2026-10-30T12:00:00Z"), Duration.ofHours(72)),
                    RecordFailure.TooFewDownloads("fresh", 10, 1_000),
                ),
            ),
            refusal,
        )
        assertEquals(
            "fresh 1.0.0 was published on 2026-10-30T12:00:00Z, less than 72 hours ago; fresh has 10 downloads, fewer than 1000",
            refusal.reason,
        )
        assertFalse(lockfile.exists())
    }

    @Test
    fun `refuses what the compatibility database rules out, before downloading anything`() {
        publish("fast", jar("fast"))
        categories["fast"] = "optimization"
        publish("renderer", jar("renderer"))
        publish("crashy", jar("crashy"))

        assertEquals(
            Outcome.Err(
                AddRefusal.Unsuitable(listOf(RecordFailure.DeniedCategory("fast", "optimization", "it replaces systems built at start"))),
            ),
            add("fast"),
        )
        assertEquals(
            Outcome.Err(AddRefusal.Unsuitable(listOf(RecordFailure.DeniedProject("renderer", "it replaces the renderer")))),
            add("renderer"),
        )
        assertEquals(
            "version vcrashy failed with Rloaded 0.1.0: crashes on join",
            (add("crashy") as Outcome.Err).error.reason,
        )
        assertEquals(0, server.hits("/cdn/fast.jar") + server.hits("/cdn/renderer.jar") + server.hits("/cdn/crashy.jar"))
    }

    @Test
    fun `refuses a version the server recorded as broken after it started`() {
        publish("flaky", jar("flaky"))
        publish("steady", jar("steady"))
        assertIs<Outcome.Ok<*>>(add("steady"))

        assertEquals(Outcome.Ok(true), local.recordBroken("vflaky", "0.2.0", "failed to load on a player's game"))

        assertEquals(
            "version vflaky failed with Rloaded 0.2.0: failed to load on a player's game",
            (add("flaky") as Outcome.Err).error.reason,
        )
        assertEquals(0, server.hits("/cdn/flaky.jar"))
    }

    @Test
    fun `refuses a lockfile it cannot read`() {
        lockfile.parent.toFile().mkdirs()
        lockfile.writeText("{}")

        assertIs<AddRefusal.LockfileProblem>((add("menu") as Outcome.Err).error)
        assertIs<LockfileError.Malformed>(((add("menu") as Outcome.Err).error as AddRefusal.LockfileProblem).error)
    }

    @Test
    fun `runs one add at a time`() {
        publish("slow", jar("slow"))
        val checking = CountDownLatch(1)
        val release = CountDownLatch(1)
        whileChecking = {
            checking.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val first = service.addAsync("slow", null, "console")
        checking.await(5, TimeUnit.SECONDS)

        val second = add("slow")
        release.countDown()

        assertEquals(Outcome.Err(AddRefusal.Busy), second)
        assertIs<Outcome.Ok<*>>(first.get(5, TimeUnit.SECONDS))
    }

    private fun add(project: String) = runBlocking { service.add(project, null, "console") }

    // One service for the whole test, so concurrent adds share its lock.
    private val service by lazy {
        val modrinth = Modrinth(http, ModrinthSettings(apiUrl = server.url("/v2")))
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        AdmissionService(
            Resolver(modrinth, "26.3"),
            RecordChecks(modrinth, ReputationRules(Duration.ofHours(72), 1_000), { local.current() }, clock),
            ArtifactStore(directory.resolve("store"), http, RetryPolicy(maxAttempts = 1)),
            JarRules(
                JarClassifier(emptySet(), setOf("default")),
                { jars ->
                    serverChecks += jars.map { jar -> projectOf(jar) }
                    whileChecking()
                    serverRefusal
                },
                { installed, jars ->
                    stagedWith += listOf(installed.map { jar -> projectOf(jar) })
                    staged += jars.map { jar -> projectOf(jar) }
                    stagingFailure?.let { Outcome.Err(it) } ?: Outcome.Ok(Unit)
                },
            ),
            AddTarget(
                LockfileLocation(lockfile, empty),
                { jars ->
                    applied += jars.map { jar -> projectOf(jar) }
                    whileApplying()
                    CompletableFuture.completedFuture(applyFailure?.let { Outcome.Err(it) } ?: Outcome.Ok(Unit))
                },
                game,
            ),
            clock,
        )
    }

    private val downloads = mutableMapOf<String, Long>()
    private val categories = mutableMapOf<String, String>()
    private val compat =
        CompatDatabase(
            deniedCategories = mapOf("optimization" to "it replaces systems built at start"),
            deniedProjects = mapOf("renderer" to "it replaces the renderer"),
            versions = mapOf("vcrashy" to TestedVersion(CompatStatus.BROKEN, "0.1.0", "crashes on join")),
        )
    private val local by lazy { (LocalCompat.open(directory.resolve("compat-local.json"), compat) as Outcome.Ok).value }

    init {
        // Modrinth's project records, with the download counts set in [downloads], and no team members.
        server.route("/v2/projects") { exchange, _ ->
            val asked = exchange.requestURI.query.removePrefix("ids=")
            val projects =
                downloads
                    .filterKeys { "\"$it\"" in asked }
                    .entries
                    .joinToString(",", "[", "]") { (id, count) ->
                        """{"id": "$id", "title": "$id", "downloads": $count, "team": "t$id", """ +
                            """"categories": ["${categories[id] ?: "utility"}"], "additional_categories": []}"""
                    }
            exchange.respond(200, projects.toByteArray())
        }
        server.route("/v2/teams") { exchange, _ -> exchange.respond(200, "[]".toByteArray()) }
    }

    private val jarProjects = mutableMapOf<String, String>()

    private fun projectOf(jar: Path): String =
        jarProjects.getValue(MessageDigest.getInstance("SHA-512").digest(jar.readBytes()).toHexString())

    // Serves one release of [project], for Fabric 26.3, whose file is [jar].
    private fun publish(
        project: String,
        jar: ByteArray,
        dependsOn: String? = null,
        published: String = "2026-10-01T00:00:00Z",
        downloadCount: Long = 5_000,
    ) {
        downloads[project] = downloadCount
        val hex = MessageDigest.getInstance("SHA-512").digest(jar).toHexString()
        jarProjects[hex] = project
        val dependencies = dependsOn?.let { """{"project_id": "$it", "dependency_type": "required"}""" }.orEmpty()
        val version =
            """[{"id": "v$project", "project_id": "$project", "version_number": "1.0.0", "version_type": "release", """ +
                """"date_published": "$published", "game_versions": ["26.3"], "loaders": ["fabric"], """ +
                """"dependencies": [$dependencies], "files": [{"url": "${server.url(
                    "/cdn/$project.jar",
                )}", "filename": "$project.jar", """ +
                """"size": ${jar.size}, "primary": true, "hashes": {"sha512": "$hex"}}]}]"""
        server.route("/v2/project/$project/version") { exchange, _ -> exchange.respond(200, version.toByteArray()) }
        server.serve("/cdn/$project.jar", jar)
    }

    private fun jar(
        id: String,
        environment: String = "*",
        entrypoints: String = "",
    ): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write("""{"schemaVersion": 1, "id": "$id", "version": "1.0.0", $entrypoints "environment": "$environment"}""".toByteArray())
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    private fun sha(text: String): Checksum =
        (Checksum.parse(HashAlgorithm.SHA512, MessageDigest.getInstance("SHA-512").digest(text.toByteArray()).toHexString()) as Outcome.Ok)
            .value

    private fun <T, E> ok(outcome: Outcome<T, E>): T =
        when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected a value, got ${outcome.error}")
        }
}
