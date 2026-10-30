package io.github.calamech.rloaded.core.mojang

import io.github.calamech.rloaded.core.download.Artifact
import io.github.calamech.rloaded.protocol.Checksum
import io.github.calamech.rloaded.protocol.HashAlgorithm
import io.github.calamech.rloaded.protocol.Outcome
import io.github.calamech.rloaded.protocol.flatMap
import io.github.calamech.rloaded.protocol.map
import io.github.calamech.rloaded.protocol.traverse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Turns Mojang's version metadata into a [GameVersion] for one platform and set of features. */
internal object Resolution {
    fun resolve(
        version: VersionJson,
        platform: Platform,
        features: Set<String>,
    ): Outcome<GameVersion, MetadataError> {
        val source = "version ${version.id}"
        val client = version.downloads["client"]
        val server = version.downloads["server"]
        if (client == null || server == null) return Outcome.Err(MetadataError.Malformed(source, "missing client or server download"))
        val libraries = version.libraries.filter { allowed(it.rules, platform, features) }.map { it.downloads.artifact }
        return artifacts(source, listOf(client, server) + libraries).flatMap { files ->
            artifact(source, version.assetIndex.url, version.assetIndex.sha1, version.assetIndex.size).flatMap { assetIndex ->
                logging(source, version.logging).flatMap { logging ->
                    arguments(source, version.arguments.jvm, platform, features).flatMap { jvm ->
                        arguments(source, version.arguments.game, platform, features).map { game ->
                            GameVersion(
                                id = version.id,
                                mainClass = version.mainClass,
                                javaMajorVersion = version.javaVersion.majorVersion,
                                client = files[0],
                                server = files[1],
                                libraries = files.drop(2),
                                assetIndexId = version.assetIndex.id,
                                assetIndex = assetIndex,
                                logging = logging,
                                jvmArguments = jvm,
                                gameArguments = game,
                            )
                        }
                    }
                }
            }
        }
    }

    fun assets(
        index: AssetIndexJson,
        resourcesUrl: String,
    ): Outcome<List<Asset>, MetadataError> =
        index.objects.entries.traverse { (path, item) ->
            artifact("asset $path", "$resourcesUrl/${item.hash.take(2)}/${item.hash}", item.hash, item.size).map { Asset(path, it) }
        }

    /** Mojang's rule semantics: denied unless a rule allows it; the last matching rule wins. */
    fun allowed(
        rules: List<RuleJson>,
        platform: Platform,
        features: Set<String>,
    ): Boolean =
        rules.isEmpty() ||
            rules.fold(false) { allowed, rule -> if (matches(rule, platform, features)) rule.action == RuleAction.ALLOW else allowed }

    private fun matches(
        rule: RuleJson,
        platform: Platform,
        features: Set<String>,
    ): Boolean {
        val os = rule.os
        val osMatches = os == null || (os.name in setOf(null, platform.os.mojangName) && os.arch in setOf(null, archName(platform)))
        return osMatches && rule.features.all { (name, required) -> (name in features) == required }
    }

    // Mojang's only architecture value is "x86", meaning 32-bit, which Rloaded never runs on.
    private fun archName(platform: Platform): String =
        when (platform.architecture) {
            Architecture.X86_64 -> "x86_64"
            Architecture.ARM64 -> "arm64"
        }

    private fun arguments(
        source: String,
        elements: List<JsonElement>,
        platform: Platform,
        features: Set<String>,
    ): Outcome<List<String>, MetadataError> = elements.traverse { argument(source, it, platform, features) }.map { it.flatten() }

    private fun argument(
        source: String,
        element: JsonElement,
        platform: Platform,
        features: Set<String>,
    ): Outcome<List<String>, MetadataError> =
        when {
            element is JsonPrimitive && element.isString -> {
                Outcome.Ok(listOf(element.content))
            }

            element is JsonObject -> {
                Codec.decode<List<RuleJson>>(source, element["rules"] ?: JsonArray(emptyList())).flatMap { rules ->
                    val value = element["value"]
                    when {
                        !allowed(rules, platform, features) -> {
                            Outcome.Ok(emptyList())
                        }

                        value is JsonPrimitive && value.isString -> {
                            Outcome.Ok(listOf(value.content))
                        }

                        value is JsonArray && value.all { it is JsonPrimitive && it.isString } -> {
                            Outcome.Ok(value.map { (it as JsonPrimitive).content })
                        }

                        else -> {
                            Outcome.Err(MetadataError.Malformed(source, "argument value is not a string or a list of strings"))
                        }
                    }
                }
            }

            else -> {
                Outcome.Err(MetadataError.Malformed(source, "argument is not a string or an object"))
            }
        }

    private fun logging(
        source: String,
        logging: LoggingJson?,
    ): Outcome<Logging?, MetadataError> {
        val client = logging?.client ?: return Outcome.Ok(null)
        return artifact(source, client.file.url, client.file.sha1, client.file.size).map { Logging(client.argument, client.file.id, it) }
    }

    private fun artifacts(
        source: String,
        downloads: List<DownloadJson>,
    ): Outcome<List<Artifact>, MetadataError> = downloads.traverse { artifact(source, it.url, it.sha1, it.size) }

    fun artifact(
        source: String,
        url: String,
        sha1: String,
        size: Long?,
    ): Outcome<Artifact, MetadataError> =
        when (val checksum = Checksum.parse(HashAlgorithm.SHA1, sha1)) {
            is Outcome.Ok -> Outcome.Ok(Artifact(url, checksum.value, size))
            is Outcome.Err -> Outcome.Err(MetadataError.Malformed(source, "invalid sha1 '$sha1' for $url"))
        }
}
