package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.protocol.AddProblem

/** What the Mods screen tells the player about this refusal. */
public fun AddRefusal.problem(): AddProblem =
    when (this) {
        AddRefusal.Busy -> AddProblem.BUSY

        is AddRefusal.LockfileProblem, is AddRefusal.NotRecorded, is AddRefusal.NotAudited -> AddProblem.STORAGE

        is AddRefusal.Unresolved -> error.problem()

        is AddRefusal.Unsuitable -> AddProblem.UNSUITABLE

        is AddRefusal.ProjectRecords, is AddRefusal.NotDownloaded -> AddProblem.UNAVAILABLE

        is AddRefusal.NoFile,
        is AddRefusal.Unreadable,
        is AddRefusal.Refused,
        is AddRefusal.Unlistable,
        is AddRefusal.ServerRefused,
        is AddRefusal.StagingRefused,
        -> AddProblem.UNSUPPORTED

        is AddRefusal.NotApplied -> AddProblem.FAILED
    }

private fun ResolveError.problem(): AddProblem =
    when (this) {
        is ResolveError.Records -> {
            AddProblem.UNAVAILABLE
        }

        is ResolveError.UnknownProject, is ResolveError.UnknownVersion -> {
            AddProblem.NOT_FOUND
        }

        is ResolveError.NoVersion, is ResolveError.NoRelease, is ResolveError.Prerelease, is ResolveError.DependencyNotForGame -> {
            AddProblem.NO_RELEASE
        }

        is ResolveError.AlreadyInstalled -> {
            AddProblem.ALREADY_INSTALLED
        }

        is ResolveError.Conflict, is ResolveError.Incompatible -> {
            AddProblem.CONFLICT
        }

        is ResolveError.ExternalDependency, is ResolveError.TooMany -> {
            AddProblem.UNSUPPORTED
        }
    }
