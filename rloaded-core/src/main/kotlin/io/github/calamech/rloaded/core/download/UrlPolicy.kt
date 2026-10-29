package io.github.calamech.rloaded.core.download

import java.net.URI
import java.net.URISyntaxException

/** A scheme, host and port that downloads may come from. */
public data class Origin(
    val scheme: String,
    val host: String,
    val port: Int,
)

/** Decides which URLs Rloaded may download from, including every redirect target. */
public class UrlPolicy(
    private val allowed: Set<Origin>,
) {
    /** Returns null when [url] is allowed, or the reason it is refused. */
    public fun refusalReason(url: String): String? {
        val uri =
            try {
                URI(url)
            } catch (_: URISyntaxException) {
                null
            }
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase()
        return when {
            uri == null -> {
                "not a valid URL"
            }

            scheme == null -> {
                "no scheme"
            }

            host == null -> {
                "no host"
            }

            else -> {
                val port = if (uri.port == -1) defaultPort(scheme) else uri.port
                if (Origin(scheme, host, port) in allowed) null else "$scheme://$host:$port is not an allowed origin"
            }
        }
    }

    /** Policies used by Rloaded. */
    public companion object {
        /** The hosts Rloaded downloads from, over HTTPS only. */
        public val PRODUCTION: UrlPolicy =
            UrlPolicy(
                setOf(
                    // Mojang: version metadata, game files, libraries, assets.
                    "piston-meta.mojang.com",
                    "piston-data.mojang.com",
                    "libraries.minecraft.net",
                    "resources.download.minecraft.net",
                    // Fabric: loader metadata and libraries.
                    "meta.fabricmc.net",
                    "maven.fabricmc.net",
                    // Modrinth: API and mod files.
                    "api.modrinth.com",
                    "cdn.modrinth.com",
                    // JetBrains: the Java runtime.
                    "cache-redirector.jetbrains.com",
                ).map { Origin("https", it, HTTPS_PORT) }.toSet(),
            )

        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443

        private fun defaultPort(scheme: String): Int = if (scheme == "https") HTTPS_PORT else HTTP_PORT
    }
}
