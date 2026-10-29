package io.github.calamech.rloaded.launcher

import io.github.calamech.rloaded.core.Outcome
import io.github.calamech.rloaded.core.download.DownloadError
import io.github.calamech.rloaded.core.download.HttpSettings
import io.github.calamech.rloaded.core.download.KtorHttp
import io.github.calamech.rloaded.core.download.TestServer
import io.github.calamech.rloaded.core.mojang.Architecture
import io.github.calamech.rloaded.core.mojang.LaunchFeatures
import io.github.calamech.rloaded.core.mojang.MojangEndpoints
import io.github.calamech.rloaded.core.mojang.OperatingSystem
import io.github.calamech.rloaded.core.mojang.Platform
import io.github.calamech.rloaded.core.runtime.FakeDistribution
import io.github.calamech.rloaded.core.runtime.RuntimePins
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameInstallerTest {
    private val server = TestServer()
    private val http = KtorHttp(server.policy, HttpSettings())
    private val distribution = FakeDistribution(server)
    private val mac = Platform(OperatingSystem.MACOS, Architecture.ARM64)
    private val linux = Platform(OperatingSystem.LINUX, Architecture.X86_64)

    @TempDir
    lateinit var root: Path

    private val directories get() = ClientDirectories(root)
    private val profile = (OfflineProfile.of("Dev") as Outcome.Ok).value

    @AfterEach
    fun close() {
        http.close()
        server.close()
    }

    @Test
    fun `installs the runtime and the game files`() =
        runBlocking {
            val installed = install(mac, setOf(LaunchFeatures.QUICK_PLAY_MULTIPLAYER))

            assertEquals(directories.runtimes.resolve("jbr-25-test/jbr/Contents/Home/bin/java"), installed.java)
            assertEquals(listOf("loader", "common", "mac only", "client"), installed.classpath.map { it.readText() })
            assertEquals(listOf("fabric api"), installed.mods.map { it.readText() })
            assertTrue(
                installed.mods
                    .single()
                    .toString()
                    .endsWith(".jar"),
            )
            assertEquals("<Configuration/>", installed.loggingConfig?.readText())
            assertTrue(
                directories.assets
                    .resolve("indexes/7.json")
                    .readText()
                    .contains("icons/icon.png"),
            )
            assertEquals(
                1,
                directories.assets
                    .resolve("objects")
                    .toFile()
                    .walk()
                    .count { it.isFile },
            )
        }

    @Test
    fun `leaves out the libraries of other platforms`() =
        runBlocking {
            val installed = install(linux, emptySet())

            assertEquals(listOf("loader", "common", "client"), installed.classpath.map { it.readText() })
            assertEquals(directories.runtimes.resolve("jbr-25-test/jbr/bin/java"), installed.java)
        }

    @Test
    fun `builds the client command`() =
        runBlocking {
            val installed = install(mac, setOf(LaunchFeatures.QUICK_PLAY_MULTIPLAYER))
            val gameDirectory = directories.profile("local")
            val settings = LaunchSettings(profile, gameDirectory, ServerAddress("localhost"), Path.of("/agent.jar"), "0.1.0")

            val command =
                (
                    ClientCommand.build(
                        installed,
                        settings,
                        directories.assets,
                        gameDirectory.resolve("natives"),
                    ) as Outcome.Ok
                ).value

            val classpath = installed.classpath.joinToString(File.pathSeparator)
            assertEquals(
                listOf(
                    installed.java.toString(),
                    "-Xmx4096M",
                    "-XX:+AllowEnhancedClassRedefinition",
                    "-javaagent:${Path.of("/agent.jar")}",
                    "-Dfabric.addMods=${installed.mods.single()}",
                    "-XstartOnFirstThread",
                    "-Djava.library.path=${gameDirectory.resolve("natives")}",
                    "-cp",
                    classpath,
                    "-Dlog4j.configurationFile=${installed.loggingConfig}",
                    "net.fabricmc.loader.impl.launch.knot.KnotClient",
                    "--username",
                    "Dev",
                    "--uuid",
                    profile.uuid.toString(),
                    "--accessToken",
                    "0",
                    "--gameDir",
                    gameDirectory.toString(),
                    "--assetsDir",
                    directories.assets.toString(),
                    "--assetIndex",
                    "7",
                    "--quickPlayMultiplayer",
                    "localhost",
                ),
                command,
            )
        }

    @Test
    fun `refuses to fill a placeholder it has no value for`() =
        runBlocking {
            val installed = install(mac, setOf(LaunchFeatures.QUICK_PLAY_MULTIPLAYER))
            val settings = LaunchSettings(profile, directories.profile("local"), server = null, Path.of("/agent.jar"), "0.1.0")

            assertEquals(
                Outcome.Err(UnknownPlaceholder("quickPlayMultiplayer")),
                ClientCommand.build(installed, settings, directories.assets, root.resolve("natives")),
            )
        }

    @Test
    fun `installs once and reuses the files`() =
        runBlocking {
            install(mac, emptySet())
            install(mac, emptySet())

            assertEquals(1, server.hits("/client.jar"))
            assertEquals(1, server.hits("/jbr.tar.gz"))
            assertEquals(2, server.hits("/manifest.json"))
        }

    @Test
    fun `lists every file that failed`() =
        runBlocking {
            server.serve("/libraries/common.jar", "COMMON".toByteArray())
            server.serve("/mods/fabric-api.jar", ByteArray(0), status = 503)

            val result = installer(mac).install(emptySet())

            val failure = (result as Outcome.Err).error as InstallFailure.Downloads
            assertEquals(2, failure.failures.size)
            assertTrue(failure.failures.any { it is DownloadError.ChecksumMismatch })
            assertTrue(failure.failures.any { it is DownloadError.HttpStatus })
            assertEquals(7, failure.total)
        }

    @Test
    fun `reports progress up to the total`() =
        runBlocking {
            val seen = mutableListOf<Pair<Int, Int>>()

            installer(mac).install(emptySet()) { done, total -> synchronized(seen) { seen += done to total } }

            assertEquals(7 to 7, seen.maxBy { it.first })
        }

    private suspend fun install(
        platform: Platform,
        features: Set<String>,
    ): InstalledGame = (installer(platform).install(features) as Outcome.Ok).value

    private fun installer(platform: Platform) =
        GameInstaller(
            http,
            directories,
            platform,
            RuntimePins.parse(distribution.pins),
            endpoints = MojangEndpoints(distribution.manifestUrl, distribution.resourcesUrl),
        )
}
