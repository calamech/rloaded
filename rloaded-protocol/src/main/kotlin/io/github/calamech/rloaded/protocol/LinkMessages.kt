package io.github.calamech.rloaded.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The version of the messages between the launcher and the game. A game with another version is refused. */
public const val LINK_PROTOCOL_VERSION: Int = 1

/** The first message a game sends its launcher: the token the launcher gave it at start. */
@Serializable
public data class LinkHello(
    val protocol: Int,
    val token: String,
) {
    init {
        require(protocol >= 1) { "protocol must be at least 1" }
        require(LinkLimits.TOKEN.matches(token)) { "token must be 64 lowercase hexadecimal digits" }
    }
}

/** What the launcher asks the game, for the world or server the game has open. */
@Serializable
public sealed interface LinkRequest {
    /** Identifies the request in the events that answer it. */
    public val requestId: String

    /** Add a mod from Modrinth, at [versionId] or its newest release when null. */
    @Serializable
    @SerialName("add")
    public data class Add(
        override val requestId: String,
        val projectId: String,
        val versionId: String?,
    ) : LinkRequest {
        init {
            require(LinkLimits.REQUEST_ID.matches(requestId)) { "requestId must be 1 to 64 letters, digits or dashes" }
            require(LinkLimits.PROJECT.matches(projectId)) { "projectId must be 1 to 64 letters, digits, dashes or underscores" }
            require(versionId == null || Limits.MODRINTH_ID.matches(versionId)) { "versionId must be 1 to 16 letters or digits" }
        }
    }
}

/** What the game tells the launcher about a request. */
@Serializable
public sealed interface LinkEvent {
    /** The request this event answers. */
    public val requestId: String

    /** The mods are added: [files] are their file names. */
    @Serializable
    @SerialName("added")
    public data class Added(
        override val requestId: String,
        val files: List<String>,
    ) : LinkEvent {
        init {
            require(files.size <= Limits.ENTRIES) { "at most ${Limits.ENTRIES} files" }
            require(files.all { Limits.FILE_NAME.matches(it) && it.toByteArray().size <= Limits.NAME_BYTES }) {
                "files must be jar file names without a path"
            }
        }
    }

    /**
     * The request was refused for [problem], which the launcher has a message for; [detail] says
     * more, in words for a log. Nothing was added, unless [problem] is [AddProblem.FAILED].
     */
    @Serializable
    @SerialName("refused")
    public data class Refused(
        override val requestId: String,
        val problem: AddProblem,
        val detail: String,
    ) : LinkEvent {
        init {
            require(detail.length in 1..Limits.REPORT) { "detail must have 1 to ${Limits.REPORT} characters" }
        }
    }
}

/** Why the game did not add a mod the launcher asked for. */
@Serializable
public enum class AddProblem {
    /** No singleplayer world is open. */
    NO_WORLD,

    /** The world is open to other players on the local network. */
    SHARED_WORLD,

    /** Another add is running. */
    BUSY,

    /** Modrinth has no such project or version. */
    NOT_FOUND,

    /** The mod, or a mod it needs, has no stable release for this game version. */
    NO_RELEASE,

    /** The world already has the mod. */
    ALREADY_INSTALLED,

    /** The mod conflicts with a mod the world has. */
    CONFLICT,

    /** The version is too recent, its project too little downloaded, or it is known to break. */
    UNSUITABLE,

    /** Modrinth or a download cannot be reached. */
    UNAVAILABLE,

    /** The mod cannot be added to a running game. */
    UNSUPPORTED,

    /** The mod failed as the game loaded it; the game restarts without it. */
    FAILED,

    /** The world's list of mods cannot be read or written. */
    STORAGE,
}

/** Bounds of the link's message fields. */
internal object LinkLimits {
    val TOKEN = Regex("[0-9a-f]{64}")
    val REQUEST_ID = Regex("[A-Za-z0-9-]{1,64}")

    // A Modrinth project id or slug.
    val PROJECT = Regex("[A-Za-z0-9_-]{1,64}")
}
