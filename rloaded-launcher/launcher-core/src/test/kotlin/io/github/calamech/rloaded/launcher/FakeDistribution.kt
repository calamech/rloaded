package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.core.download.TestServer
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * A complete but tiny distribution served by [server]: a version manifest, a version file with
 * platform rules, libraries, assets, Fabric Loader, a base set and a JetBrains Runtime archive.
 */
class FakeDistribution(
    private val server: TestServer,
) {
    val pins: String

    init {
        val client = serve("/client.jar", "client")
        val serverJar = serve("/server.jar", "server")
        val common = serve("/libraries/common.jar", "common")
        val macOnly = serve("/libraries/mac-only.jar", "mac only")
        val logging = serve("/logging/client.xml", "<Configuration/>")
        val icon = "icon bytes".toByteArray()
        val iconHash = sha1(icon)
        server.serve("/resources/${iconHash.take(2)}/$iconHash", icon)
        val assetIndex = serve("/indexes/7.json", """{"objects":{"icons/icon.png":{"hash":"$iconHash","size":${icon.size}}}}""")
        val version =
            """
            {"id":"26.3","mainClass":"net.minecraft.client.main.Main","javaVersion":{"majorVersion":25},
             "assetIndex":{"id":"7","sha1":"${assetIndex.sha1}","size":${assetIndex.size},"url":"${assetIndex.url}"},
             "downloads":{"client":${client.json},"server":${serverJar.json}},
             "libraries":[
               {"name":"com.example:common:1","downloads":{"artifact":${common.json}}},
               {"name":"com.example:mac-only:1","downloads":{"artifact":${macOnly.json}},"rules":[{"action":"allow","os":{"name":"osx"}}]}
             ],
             "arguments":{
               "jvm":[{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},
                      "-Djava.library.path=${'$'}{natives_directory}","-cp","${'$'}{classpath}"],
               "game":["--username","${'$'}{auth_player_name}","--uuid","${'$'}{auth_uuid}","--accessToken","${'$'}{auth_access_token}",
                       "--gameDir","${'$'}{game_directory}","--assetsDir","${'$'}{assets_root}","--assetIndex","${'$'}{assets_index_name}",
                       {"rules":[{"action":"allow","features":{"is_quick_play_multiplayer":true}}],
                        "value":["--quickPlayMultiplayer","${'$'}{quickPlayMultiplayer}"]}]},
             "logging":{"client":{"argument":"-Dlog4j.configurationFile=${'$'}{path}",
                        "file":{"id":"client.xml","sha1":"${logging.sha1}","size":${logging.size},"url":"${logging.url}"}}}}
            """.trimIndent()
        val versionFile = serve("/v1/26.3.json", version)
        server.serve(
            "/manifest.json",
            """{"versions":[{"id":"26.3","url":"${versionFile.url}","sha1":"${versionFile.sha1}"}]}""".toByteArray(),
        )

        val loader = serve("/fabric/fabric-loader.jar", "loader")
        val mod = serve("/mods/fabric-api.jar", "fabric api")
        val runtime = serveBytes("/jbr.tar.gz", runtimeArchive())
        val archives =
            listOf("macos-arm64", "macos-x86_64", "linux-x86_64", "linux-arm64", "windows-x86_64", "windows-arm64")
                .joinToString(",") { """"$it":{"url":"${runtime.url}","sha512":"${runtime.sha512}"}""" }
        pins =
            """
            {"recorded":"2026-10-29","minecraft":"26.3",
             "fabricLoader":{"version":"0.20.1","libraries":[{"url":"${loader.url}","size":${loader.size},"sha512":"${loader.sha512}"}]},
             "baseSet":[{"projectId":"P7dR8mSH","versionId":"v1","fileName":"fabric-api.jar","url":"${mod.url}","size":${mod.size},
                         "sha512":"${mod.sha512}"}],
             "jetBrainsRuntime":{"version":"25-test","archives":{$archives}}}
            """.trimIndent()
    }

    val manifestUrl: String get() = server.url("/manifest.json")
    val resourcesUrl: String get() = server.url("/resources")

    class Served(
        val url: String,
        val size: Int,
        val sha1: String,
        val sha512: String,
    ) {
        val json: String get() = """{"sha1":"$sha1","size":$size,"url":"$url"}"""
    }

    private fun serve(
        path: String,
        content: String,
    ): Served = serveBytes(path, content.toByteArray())

    private fun serveBytes(
        path: String,
        bytes: ByteArray,
    ): Served {
        server.serve(path, bytes)
        return Served(server.url(path), bytes.size, sha1(bytes), MessageDigest.getInstance("SHA-512").digest(bytes).toHexString())
    }

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).toHexString()

    // Contains a java executable for every platform layout.
    private fun runtimeArchive(): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(GZIPOutputStream(bytes)).use { tar ->
            for (name in listOf("jbr/Contents/Home/bin/java", "jbr/bin/java", "jbr/bin/java.exe")) {
                val content = "java".toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).also { it.size = content.size.toLong() })
                tar.write(content)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }
}
