package io.github.calamech.rloaded.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The version of the messages in this file. A peer with another version is refused. */
public const val PROTOCOL_VERSION: Int = 1

/** Sent by the server when a client starts configuration. */
@Serializable
public data class ServerHello(
    val protocol: Int,
    val serverKey: ServerKey,
    val serverName: String,
    /** SHA-256 of the canonical lockfile, so a client can tell when it changed. */
    @Serializable(with = Sha256Serializer::class)
    val lockfileHash: Checksum,
) {
    init {
        require(protocol >= 1) { "protocol must be at least 1" }
        require(serverName.length in 1..Limits.NAME) { "serverName must have 1 to ${Limits.NAME} characters" }
        require(lockfileHash.algorithm == HashAlgorithm.SHA256) { "lockfileHash must be a SHA-256 checksum" }
    }
}

/** Sent by the client in answer to [ServerHello]. */
@Serializable
public data class ClientHello(
    val protocol: Int,
    val rloadedVersion: String,
    /** SHA-256 of the canonical list of mods the client's game has loaded. */
    @Serializable(with = Sha256Serializer::class)
    val loadedSetHash: Checksum,
) {
    init {
        require(protocol >= 1) { "protocol must be at least 1" }
        require(rloadedVersion.length in 1..Limits.VERSION) { "rloadedVersion must have 1 to ${Limits.VERSION} characters" }
        require(loadedSetHash.algorithm == HashAlgorithm.SHA256) { "loadedSetHash must be a SHA-256 checksum" }
    }
}

/** Which game installs a mod. */
@Serializable
public enum class Side {
    BOTH,
    CLIENT,
    SERVER,
}

/** One mod file, as Modrinth publishes it. */
@Serializable
public data class ModEntry(
    val projectId: String,
    val versionId: String,
    val fileName: String,
    @Serializable(with = Sha512Serializer::class)
    val sha512: Checksum,
    val size: Long,
    val side: Side,
) {
    init {
        require(Limits.MODRINTH_ID.matches(projectId)) { "projectId must be 1 to 16 letters or digits" }
        require(Limits.MODRINTH_ID.matches(versionId)) { "versionId must be 1 to 16 letters or digits" }
        require(Limits.FILE_NAME.matches(fileName) && fileName.length <= Limits.NAME_BYTES) {
            "fileName must be a .jar name of at most ${Limits.NAME_BYTES} characters, without a path"
        }
        require(sha512.algorithm == HashAlgorithm.SHA512) { "sha512 must be a SHA-512 checksum" }
        require(size in 1..Limits.FILE_SIZE) { "size must be between 1 and ${Limits.FILE_SIZE} bytes" }
    }
}

/** Whether a manifest lists every mod of the server, or only the mods an add brings. */
@Serializable
public enum class ManifestKind {
    FULL,
    DELTA,
}

/** The mods a client must load, signed by the server's key. Build one with [ManifestSigning.sign]. */
@Serializable
public data class Manifest(
    val kind: ManifestKind,
    val entries: List<ModEntry>,
    val signature: ManifestSignature,
) {
    init {
        require(entries.size <= Limits.ENTRIES) { "a manifest has at most ${Limits.ENTRIES} entries" }
        require(entries.map { it.projectId }.toSet().size == entries.size) { "a manifest lists each project once" }
    }
}

/** The step a client is at while it installs a manifest. */
@Serializable
public enum class InstallStage {
    TRUST,
    DOWNLOAD,
    VERIFY,
    LOAD,
    RELOAD,
}

/** Sent by the client about once a second while it installs; it also tells the server the client is alive. */
@Serializable
public data class Progress(
    val stage: InstallStage,
    val percent: Int,
) {
    init {
        require(percent in 0..Limits.PERCENT) { "percent must be between 0 and ${Limits.PERCENT}" }
    }
}

/** How an install ended on a client. */
@Serializable
public sealed interface InstallResult {
    /** Every mod of the manifest is loaded. */
    @Serializable
    @SerialName("loaded")
    public data object Loaded : InstallResult

    /** The player did not trust the server. */
    @Serializable
    @SerialName("refused_by_user")
    public data object RefusedByUser : InstallResult

    /** The install failed; [failingMod] is the project at fault when it is known. */
    @Serializable
    @SerialName("failed")
    public data class Failed(
        val failingMod: String?,
        val report: String,
    ) : InstallResult {
        init {
            require(failingMod == null || Limits.MODRINTH_ID.matches(failingMod)) { "failingMod must be 1 to 16 letters or digits" }
            require(report.length in 1..Limits.REPORT) { "report must have 1 to ${Limits.REPORT} characters" }
        }
    }
}

/** Bounds of the message fields. */
internal object Limits {
    const val NAME = 64
    const val VERSION = 32
    const val NAME_BYTES = 255
    const val FILE_SIZE = 512L * 1024 * 1024
    const val ENTRIES = 1024
    const val PERCENT = 100
    const val REPORT = 16 * 1024
    val MODRINTH_ID = Regex("[A-Za-z0-9]{1,16}")
    val FILE_NAME = Regex("""[^/\\:\p{Cntrl}]+\.jar""")
}
