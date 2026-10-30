package io.github.calamech.rloaded.admin

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
import java.time.Instant
import java.time.ZoneOffset
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
    private var whileChecking: () -> Unit = {}
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
    }

    @Test
    fun `keeps the mods already in the lockfile and does not add them again`() {
        publish("library", jar("library"))
        publish("menu", jar("menu"), dependsOn = "library")
        ok(add("library"))

        val added = ok(add("menu"))

        assertEquals(listOf("menu"), added.map { it.entry.projectId })
        assertEquals(listOf("library", "menu"), ok(Lockfile.read(lockfile)).mods.map { it.entry.projectId })
    }

    @Test
    fun `writes nothing when a jar breaks a rule, when the server refuses, or when a file is missing`() {
        publish("early", jar("early", entrypoints = """"entrypoints": {"preLaunch": ["a.Early"]},"""))
        assertEquals(Outcome.Err(AddRefusal.Refused(listOf(Refusal.PreLaunch("early")))), add("early"))

        publish("needy", jar("needy"))
        serverRefusal = "needy.jar is refused: it depends on missing-mod"
        assertEquals(Outcome.Err(AddRefusal.ServerRefused("needy.jar is refused: it depends on missing-mod")), add("needy"))
        serverRefusal = null

        publish("gone", jar("gone"))
        server.serve("/cdn/gone.jar", ByteArray(0), status = 404)
        assertIs<AddRefusal.NotDownloaded>((add("gone") as Outcome.Err).error)

        server.serve("/v2/project/unknown/version", ByteArray(0), status = 404)
        assertEquals(Outcome.Err(AddRefusal.Unresolved(ResolveError.UnknownProject("unknown"))), add("unknown"))

        assertFalse(lockfile.exists())
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
        AdmissionService(
            Resolver(Modrinth(http, ModrinthSettings(apiUrl = server.url("/v2"))), "26.3"),
            ArtifactStore(directory.resolve("store"), http, RetryPolicy(maxAttempts = 1)),
            JarClassifier(emptySet(), setOf("default")),
            { jars ->
                serverChecks += jars.map { jar -> projectOf(jar) }
                whileChecking()
                serverRefusal
            },
            LockfileLocation(lockfile, empty),
            Clock.fixed(now, ZoneOffset.UTC),
        )
    }
    private val jarProjects = mutableMapOf<String, String>()

    private fun projectOf(jar: Path): String =
        jarProjects.getValue(MessageDigest.getInstance("SHA-512").digest(jar.readBytes()).toHexString())

    // Serves one release of [project], for Fabric 26.3, whose file is [jar].
    private fun publish(
        project: String,
        jar: ByteArray,
        dependsOn: String? = null,
    ) {
        val hex = MessageDigest.getInstance("SHA-512").digest(jar).toHexString()
        jarProjects[hex] = project
        val dependencies = dependsOn?.let { """{"project_id": "$it", "dependency_type": "required"}""" }.orEmpty()
        val version =
            """[{"id": "v$project", "project_id": "$project", "version_number": "1.0.0", "version_type": "release", """ +
                """"date_published": "2026-10-01T00:00:00Z", "game_versions": ["26.3"], "loaders": ["fabric"], """ +
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
