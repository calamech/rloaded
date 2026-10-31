package io.github.calamech.rloaded.admin.staging

import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a staging server did with the mods it was given, and what went wrong. Written by the
 * staging server when it stops, read by the server that started it.
 */
@Serializable
public data class StagingReport(
    /** Ids of the mods loaded, in the order their entrypoints ran. */
    val mods: List<String>,
    val blocksPlaced: Int,
    val entitiesSpawned: Int,
    val itemsDropped: Int,
    val ticks: Int,
    /** Each failure, for the admin; empty when the mods passed. */
    val failures: List<String>,
) {
    /** Whether the mods passed. */
    public val passed: Boolean get() = failures.isEmpty()

    /** This report with [failure] added, unless [MAX_FAILURES] are already listed; long ones are cut. */
    public fun failing(failure: String): StagingReport =
        if (failures.size == MAX_FAILURES) {
            this
        } else {
            copy(failures = failures + failure.take(MAX_FAILURE_CHARACTERS))
        }

    /** This report with what the staging server exercised. */
    public fun exercised(
        blocksPlaced: Int,
        entitiesSpawned: Int,
        itemsDropped: Int,
        ticks: Int,
    ): StagingReport = copy(blocksPlaced = blocksPlaced, entitiesSpawned = entitiesSpawned, itemsDropped = itemsDropped, ticks = ticks)

    /** Writes this report to [path]. Fails with the reason when it cannot. */
    public fun write(path: Path): Outcome<Unit, String> =
        try {
            Files.writeString(path, JSON.encodeToString(serializer(), this))
            Outcome.Ok(Unit)
        } catch (e: IOException) {
            Outcome.Err("cannot write $path: ${e.message}")
        }

    /** Starting and reading reports. */
    public companion object {
        /** Failures a report lists at most. */
        public const val MAX_FAILURES: Int = 20

        /** Characters a failure keeps at most. */
        public const val MAX_FAILURE_CHARACTERS: Int = 500

        private const val MAX_BYTES: Long = 64L * 1024

        private val JSON = Json { ignoreUnknownKeys = false }

        /** A report on [mods] before anything was exercised. */
        public fun of(mods: List<String>): StagingReport = StagingReport(mods, 0, 0, 0, 0, emptyList())

        /** Reads the report at [path]; an unknown field, a missing one or more failures than allowed refuse it. */
        public fun read(path: Path): Outcome<StagingReport, String> =
            try {
                if (Files.size(path) > MAX_BYTES) {
                    Outcome.Err("the report is larger than $MAX_BYTES bytes")
                } else {
                    val report = JSON.decodeFromString(serializer(), Files.readString(path))
                    if (report.failures.size > MAX_FAILURES) {
                        Outcome.Err("the report lists more than $MAX_FAILURES failures")
                    } else {
                        Outcome.Ok(report)
                    }
                }
            } catch (e: IOException) {
                Outcome.Err("cannot read the report: ${e.message}")
            } catch (e: SerializationException) {
                Outcome.Err("the report is invalid: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Outcome.Err("the report is invalid: ${e.message}")
            }
    }
}
