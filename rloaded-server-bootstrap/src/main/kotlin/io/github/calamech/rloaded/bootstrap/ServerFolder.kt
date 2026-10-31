package io.github.calamech.rloaded.bootstrap

import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.reader
import kotlin.io.path.writeText
import kotlin.io.path.writer

/** The files of a server folder that the bootstrap reads or writes. */
public class ServerFolder(
    /** The folder the admin starts the bootstrap in. */
    public val root: Path,
) {
    /** Rloaded's settings. */
    public val config: Path get() = root.resolve("rloaded-server.toml")

    /** The Minecraft EULA acceptance file. */
    public val eula: Path get() = root.resolve("eula.txt")

    /** Minecraft's server settings. */
    public val serverProperties: Path get() = root.resolve("server.properties")

    /** Rloaded's own data: cache, runtimes, agent. */
    public val data: Path get() = root.resolve("rloaded")

    /** How the bootstrap last started the server, for the server to start a staging server alike. */
    public val serverLaunch: Path get() = data.resolve("server-launch.json")

    /** The mods the server runs and sends its players. */
    public val lockfile: Path get() = data.resolve("lockfile.json")

    /** Writes the files of a first start. Returns false when the folder was already set up. */
    public fun initialize(): Boolean {
        if (config.exists()) return false
        config.writeText(ServerConfig.TEMPLATE)
        // Accepting the EULA is the admin's decision: the file is written with eula=false.
        if (!eula.exists()) eula.writeText("# https://aka.ms/MinecraftEULA\neula=false\n")
        return true
    }

    /** Whether the admin accepted the Minecraft EULA in eula.txt. */
    public fun eulaAccepted(): Boolean = eula.exists() && properties(eula).getProperty("eula")?.trim() == "true"

    /**
     * Prepares server.properties for development with offline accounts: online-mode=false, and a
     * bind address that only this machine or a private network can reach. Returns why it refused,
     * or null.
     */
    public fun prepareDevMode(): String? {
        val properties = if (serverProperties.exists()) properties(serverProperties) else Properties()
        val bind = properties.getProperty("server-ip").orEmpty().trim()
        if (bind.isEmpty()) {
            properties.setProperty("server-ip", "127.0.0.1")
        } else if (!isLocal(bind)) {
            return "--dev turns off account checks, so the server must bind to this machine or a private network, not $bind"
        }
        properties.setProperty("online-mode", "false")
        serverProperties.writer().use { properties.store(it, "Minecraft server properties") }
        return null
    }

    private fun isLocal(address: String): Boolean =
        try {
            val inet = InetAddress.getByName(address)
            inet.isLoopbackAddress || inet.isSiteLocalAddress
        } catch (_: UnknownHostException) {
            false
        }

    private fun properties(file: Path): Properties = Properties().also { properties -> file.reader().use { properties.load(it) } }

    /** The configured server name, or the folder name when it is empty. */
    public fun serverName(config: ServerConfig): String =
        config.server.name.ifBlank {
            root
                .toAbsolutePath()
                .normalize()
                .fileName
                .toString()
        }

    /** Reads the settings file. */
    public fun readConfig(): String = config.readText()
}
