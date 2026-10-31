package io.github.calamech.rloaded.admin

import io.github.calamech.rloaded.admin.staging.StagingFailure
import io.github.calamech.rloaded.core.admission.JarProblem
import io.github.calamech.rloaded.core.admission.Refusal
import io.github.calamech.rloaded.core.download.DownloadFailures
import io.github.calamech.rloaded.core.lockfile.LockfileError
import io.github.calamech.rloaded.modrinth.ModrinthError
import io.github.calamech.rloaded.protocol.AddProblem
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

class AddProblemsTest {
    private val unwritable = LockfileError.Unwritable(Path.of("lockfile.json"), "read-only")

    @Test
    fun `tells the launcher what kind of refusal each one is`() {
        val expected =
            mapOf(
                AddRefusal.Busy to AddProblem.BUSY,
                AddRefusal.LockfileProblem(unwritable) to AddProblem.STORAGE,
                AddRefusal.NotRecorded(unwritable) to AddProblem.STORAGE,
                AddRefusal.Unsuitable(listOf(RecordFailure.TooFewDownloads("p", 1, 1_000))) to AddProblem.UNSUITABLE,
                AddRefusal.ProjectRecords(ModrinthError.InvalidId("?")) to AddProblem.UNAVAILABLE,
                AddRefusal.NotDownloaded(DownloadFailures(emptyList(), 1)) to AddProblem.UNAVAILABLE,
                AddRefusal.NoFile("v") to AddProblem.UNSUPPORTED,
                AddRefusal.Unreadable(JarProblem.NotAMod("a.jar")) to AddProblem.UNSUPPORTED,
                AddRefusal.Refused(listOf(Refusal.PreLaunch("m"))) to AddProblem.UNSUPPORTED,
                AddRefusal.Unlistable("v", "a path in the file name") to AddProblem.UNSUPPORTED,
                AddRefusal.ServerRefused("it contains nested jars") to AddProblem.UNSUPPORTED,
                AddRefusal.StagingRefused(StagingFailure.NotStarted("no java")) to AddProblem.UNSUPPORTED,
                AddRefusal.NotApplied("loading them threw") to AddProblem.FAILED,
                unresolved(ResolveError.Records(ModrinthError.InvalidId("?"))) to AddProblem.UNAVAILABLE,
                unresolved(ResolveError.UnknownProject("p")) to AddProblem.NOT_FOUND,
                unresolved(ResolveError.UnknownVersion("p", "v")) to AddProblem.NOT_FOUND,
                unresolved(ResolveError.NoVersion("p", "26.3")) to AddProblem.NO_RELEASE,
                unresolved(ResolveError.NoRelease("p")) to AddProblem.NO_RELEASE,
                unresolved(ResolveError.Prerelease("p", "1.0-beta")) to AddProblem.NO_RELEASE,
                unresolved(ResolveError.DependencyNotForGame("v")) to AddProblem.NO_RELEASE,
                unresolved(ResolveError.AlreadyInstalled("p")) to AddProblem.ALREADY_INSTALLED,
                unresolved(ResolveError.Conflict("p", "v1", "v2")) to AddProblem.CONFLICT,
                unresolved(ResolveError.Incompatible("v", "p")) to AddProblem.CONFLICT,
                unresolved(ResolveError.ExternalDependency("v")) to AddProblem.UNSUPPORTED,
                unresolved(ResolveError.TooMany(64)) to AddProblem.UNSUPPORTED,
            )

        assertEquals(expected, expected.mapValues { (refusal, _) -> refusal.problem() })
    }

    private fun unresolved(error: ResolveError) = AddRefusal.Unresolved(error)
}
