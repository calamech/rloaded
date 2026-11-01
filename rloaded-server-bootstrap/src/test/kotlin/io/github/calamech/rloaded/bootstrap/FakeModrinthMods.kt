package io.github.calamech.rloaded.bootstrap

import io.github.calamech.rloaded.core.download.ArtifactStore
import io.github.calamech.rloaded.core.download.Http
import io.github.calamech.rloaded.core.download.RetryPolicy
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.core.download.respond
import io.github.calamech.rloaded.modrinth.ModInstaller
import io.github.calamech.rloaded.modrinth.Modrinth
import io.github.calamech.rloaded.modrinth.ModrinthSettings
import java.io.OutputStream
import java.io.PrintStream
import java.net.URLDecoder
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock

/** A mod of a test lockfile: Modrinth's record of it is served by [FakeModrinthMods]. */
data class LockedJar(
    val projectId: String,
    val side: String,
    val bytes: ByteArray = "jar of $projectId".toByteArray(),
) {
    val versionId: String get() = "v$projectId"
    val fileName: String get() = "$projectId.jar"
    val sha512: String get() = MessageDigest.getInstance("SHA-512").digest(bytes).toHexString()
}

/** Serves Modrinth's records and files of [LockedJar]s from [server], and writes lockfiles that list them. */
class FakeModrinthMods(
    private val server: TestServer,
) {
    /** Answers the versions endpoint with the records of [mods] it is asked for, as Modrinth does, and serves their files. */
    fun publish(vararg mods: LockedJar) {
        server.route("/v2/versions") { exchange, _ ->
            val asked = URLDecoder.decode(exchange.requestURI.rawQuery.removePrefix("ids="), Charsets.UTF_8)
            val versions =
                mods.filter { "\"${it.versionId}\"" in asked }.joinToString(",", "[", "]") { mod ->
                    val url = server.url("/cdn/${mod.fileName}")
                    """{"id": "${mod.versionId}", "project_id": "${mod.projectId}", "files": [{"url": "$url", """ +
                        """"filename": "${mod.fileName}", "size": ${mod.bytes.size}, "hashes": {"sha512": "${mod.sha512}"}, """ +
                        """"primary": true}], "version_number": "1.0.0", "version_type": "release", """ +
                        """"date_published": "2026-10-01T00:00:00Z", """ +
                        """"game_versions": ["26.3"], "loaders": ["fabric"], "dependencies": []}"""
                }
            exchange.respond(OK, versions.toByteArray())
        }
        mods.forEach { server.serve("/cdn/${it.fileName}", it.bytes) }
    }

    /** Lockfile text listing [mods]. */
    fun lockfile(vararg mods: LockedJar): String {
        val entries =
            mods.joinToString(",") { mod ->
                """{"projectId": "${mod.projectId}", "versionId": "${mod.versionId}", "fileName": "${mod.fileName}", """ +
                    """"sha512": "${mod.sha512}", "size": ${mod.bytes.size}, "side": "${mod.side}", """ +
                    """"addedAt": "2026-10-30T12:00:00Z", "addedBy": "console"}"""
            }
        return """{"schema": 1, "minecraft": "26.3", "rloaded": "0.1.0", "baseSet": [], "mods": [$entries], "pendingRemovals": []}"""
    }

    /** Lockfile mods read from this fake Modrinth into [store], without retries. */
    fun lockfileMods(
        http: Http,
        store: Path,
    ): LockfileMods =
        LockfileMods(
            ModInstaller(
                Modrinth(http, ModrinthSettings(apiUrl = server.url("/v2"))),
                ArtifactStore(store, http, RetryPolicy(maxAttempts = 1)),
            ),
            PendingRemovals(ServerFolder(store.resolveSibling("server")), Clock.systemUTC(), PrintStream(OutputStream.nullOutputStream())),
        )

    private companion object {
        const val OK = 200
    }
}
