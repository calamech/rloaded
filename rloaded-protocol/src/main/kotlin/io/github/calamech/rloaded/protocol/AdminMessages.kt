package io.github.calamech.rloaded.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a player's Mods screen asks the server: the world's own server in singleplayer. */
@Serializable
public sealed interface AdminRequest {
    /** Add a mod from Modrinth, at [versionId] or its newest release when null. */
    @Serializable
    @SerialName("add")
    public data class Add(
        /** Identifies the request in the event that answers it. */
        val requestId: String,
        val projectId: String,
        val versionId: String?,
    ) : AdminRequest {
        init {
            require(AdminLimits.REQUEST_ID.matches(requestId)) { "requestId must be 1 to 64 letters, digits or dashes" }
            require(AdminLimits.PROJECT.matches(projectId)) { "projectId must be 1 to 64 letters, digits, dashes or underscores" }
            require(versionId == null || Limits.MODRINTH_ID.matches(versionId)) { "versionId must be 1 to 16 letters or digits" }
        }
    }

    /** Tell me the mods the server has now. */
    @Serializable
    @SerialName("mods")
    public data object ListMods : AdminRequest

    /** Remove [projectId] at the next start of the server. */
    @Serializable
    @SerialName("remove")
    public data class Remove(
        val requestId: String,
        val projectId: String,
    ) : AdminRequest {
        init {
            require(AdminLimits.REQUEST_ID.matches(requestId)) { "requestId must be 1 to 64 letters, digits or dashes" }
            require(Limits.MODRINTH_ID.matches(projectId)) { "projectId must be a Modrinth id" }
        }
    }

    /** Keep [projectId], marked for removal. */
    @Serializable
    @SerialName("keep")
    public data class Keep(
        val requestId: String,
        val projectId: String,
    ) : AdminRequest {
        init {
            require(AdminLimits.REQUEST_ID.matches(requestId)) { "requestId must be 1 to 64 letters, digits or dashes" }
            require(Limits.MODRINTH_ID.matches(projectId)) { "projectId must be a Modrinth id" }
        }
    }
}

/** What the server tells a player's Mods screen. */
@Serializable
public sealed interface AdminEvent {
    /**
     * Whether the player may add mods, and remove them; sent each time the player enters the world.
     */
    @Serializable
    @SerialName("access")
    public data class Access(
        val allowed: Boolean,
        val removals: Boolean,
    ) : AdminEvent

    /**
     * The Modrinth project ids of the mods the lockfile lists, in its order, and those of them
     * removed at the next start.
     */
    @Serializable
    @SerialName("mods")
    public data class Mods(
        val projectIds: List<String>,
        val pendingRemovals: List<String>,
    ) : AdminEvent {
        init {
            require(projectIds.size <= Limits.ENTRIES) { "at most ${Limits.ENTRIES} mods" }
            require(projectIds.all { Limits.MODRINTH_ID.matches(it) }) { "projectIds must be Modrinth ids" }
            require(pendingRemovals.all { it in projectIds }) { "pendingRemovals must be among projectIds" }
        }
    }

    /** The mod is marked for removal, or kept, as the request asked. */
    @Serializable
    @SerialName("changed")
    public data class Changed(
        val requestId: String,
    ) : AdminEvent

    /**
     * The removal, or the keep, was refused for [problem]; [neededBy] names the mods that need it
     * when the problem is [RemovalProblem.NEEDED], and [detail] says more, for a log.
     */
    @Serializable
    @SerialName("removal_refused")
    public data class RemovalRefused(
        val requestId: String,
        val problem: RemovalProblem,
        val neededBy: List<String>,
        val detail: String,
    ) : AdminEvent {
        init {
            require(detail.length in 1..Limits.REPORT) { "detail must have 1 to ${Limits.REPORT} characters" }
            require(neededBy.size <= Limits.ENTRIES && neededBy.all { it.length in 1..Limits.NAME }) { "neededBy must be mod titles" }
        }
    }

    /** The mods are added and every player has them: [files] are their file names. */
    @Serializable
    @SerialName("added")
    public data class Added(
        val requestId: String,
        val files: List<String>,
    ) : AdminEvent {
        init {
            require(files.size <= Limits.ENTRIES) { "at most ${Limits.ENTRIES} files" }
            require(files.all { Limits.FILE_NAME.matches(it) && it.toByteArray().size <= Limits.NAME_BYTES }) {
                "files must be jar file names without a path"
            }
        }
    }

    /**
     * The request was refused for [problem], which the screen has a message for; [detail] says
     * more, in words for a log. Nothing was added, unless [problem] is [AddProblem.FAILED].
     */
    @Serializable
    @SerialName("refused")
    public data class Refused(
        val requestId: String,
        val problem: AddProblem,
        val detail: String,
    ) : AdminEvent {
        init {
            require(detail.length in 1..Limits.REPORT) { "detail must have 1 to ${Limits.REPORT} characters" }
        }
    }
}

/** Why the server did not add a mod a player asked for. */
@Serializable
public enum class AddProblem {
    /** The player may not add mods here. */
    NOT_ALLOWED,

    /** The singleplayer world is open to other players on the local network. */
    SHARED_WORLD,

    /** Another add is running. */
    BUSY,

    /** Modrinth has no such project or version. */
    NOT_FOUND,

    /** The mod, or a mod it needs, has no stable release for this game version. */
    NO_RELEASE,

    /** The server already has the mod. */
    ALREADY_INSTALLED,

    /** The mod conflicts with a mod the server has. */
    CONFLICT,

    /** The version is too recent, its project too little downloaded, or it is known to break. */
    UNSUITABLE,

    /** Modrinth or a download cannot be reached. */
    UNAVAILABLE,

    /** The mod cannot be added to a running game. */
    UNSUPPORTED,

    /** The mod failed as the game loaded it; the game restarts without it. */
    FAILED,

    /** The list of mods cannot be read or written. */
    STORAGE,
}

/** Why the server did not mark a mod for removal, or did not keep one marked. */
@Serializable
public enum class RemovalProblem {
    /** The player may not change the mods here. */
    NOT_ALLOWED,

    /** An add or another change is running. */
    BUSY,

    /** The server does not have the mod. */
    NOT_INSTALLED,

    /** The mod is part of the base set every server runs. */
    BASE_SET,

    /** The mod is already marked for removal. */
    ALREADY_MARKED,

    /** The mod is not marked for removal. */
    NOT_MARKED,

    /** Other mods of the server need it. */
    NEEDED,

    /** Modrinth cannot be reached. */
    UNAVAILABLE,

    /** The list of mods cannot be read or written. */
    STORAGE,
}

/** Bounds of the admin messages' fields. */
internal object AdminLimits {
    val REQUEST_ID = Regex("[A-Za-z0-9-]{1,64}")

    // A Modrinth project id or slug.
    val PROJECT = Regex("[A-Za-z0-9_-]{1,64}")
}
