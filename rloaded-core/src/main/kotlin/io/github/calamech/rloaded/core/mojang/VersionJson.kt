package io.github.calamech.rloaded.core.mojang

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire formats of Mojang's launcher metadata. Unknown fields are ignored on purpose: Mojang adds
// fields over time, and only the ones below matter to Rloaded.

@Serializable
internal data class VersionManifestJson(
    val versions: List<ManifestEntryJson>,
)

@Serializable
internal data class ManifestEntryJson(
    val id: String,
    val url: String,
    val sha1: String,
)

@Serializable
internal data class VersionJson(
    val id: String,
    val mainClass: String,
    val javaVersion: JavaVersionJson,
    val assetIndex: AssetIndexRefJson,
    val downloads: Map<String, DownloadJson>,
    val libraries: List<LibraryJson>,
    val arguments: ArgumentsJson,
    val logging: LoggingJson? = null,
)

@Serializable
internal data class JavaVersionJson(
    val majorVersion: Int,
)

@Serializable
internal data class AssetIndexRefJson(
    val id: String,
    val sha1: String,
    val size: Long,
    val url: String,
)

@Serializable
internal data class DownloadJson(
    val sha1: String,
    val size: Long,
    val url: String,
)

@Serializable
internal data class LibraryJson(
    val name: String,
    val downloads: LibraryDownloadsJson,
    val rules: List<RuleJson> = emptyList(),
)

@Serializable
internal data class LibraryDownloadsJson(
    val artifact: DownloadJson,
)

@Serializable
internal data class RuleJson(
    val action: RuleAction,
    val os: OsRuleJson? = null,
    val features: Map<String, Boolean> = emptyMap(),
)

@Serializable
internal enum class RuleAction {
    @SerialName("allow")
    ALLOW,

    @SerialName("disallow")
    DISALLOW,
}

@Serializable
internal data class OsRuleJson(
    val name: String? = null,
    val arch: String? = null,
)

/** Each argument is either a plain string or an object with rules and one or more values. */
@Serializable
internal data class ArgumentsJson(
    val game: List<JsonElement>,
    val jvm: List<JsonElement>,
)

@Serializable
internal data class LoggingJson(
    val client: LoggingConfigJson? = null,
)

@Serializable
internal data class LoggingConfigJson(
    val argument: String,
    val file: LoggingFileJson,
)

@Serializable
internal data class LoggingFileJson(
    val id: String,
    val sha1: String,
    val size: Long,
    val url: String,
)

@Serializable
internal data class AssetIndexJson(
    val objects: Map<String, AssetObjectJson>,
)

@Serializable
internal data class AssetObjectJson(
    val hash: String,
    val size: Long,
)
