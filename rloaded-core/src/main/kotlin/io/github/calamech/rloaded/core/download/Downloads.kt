package io.github.calamech.rloaded.core.download

import io.github.calamech.rloaded.protocol.Outcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** One file to obtain from a store, with the suffix its stored name gets. */
public data class Download(
    val store: ArtifactStore,
    val artifact: Artifact,
    val suffix: String = "",
)

/** Downloads that failed, all of them, out of [total]. */
public data class DownloadFailures(
    val failures: List<DownloadError>,
    val total: Int,
)

/** Obtains many files at once, with bounded parallelism. */
public object Downloads {
    /**
     * Obtains every download, at most [parallelism] at a time, and returns their paths in order.
     * Every download runs to its end; failures are all reported, none is dropped.
     * [onProgress] receives the index of the download that just ended, the number done and the total.
     */
    public suspend fun obtainAll(
        downloads: List<Download>,
        parallelism: Int,
        onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> },
    ): Outcome<List<Path>, DownloadFailures> =
        coroutineScope {
            val permits = Semaphore(parallelism)
            val done = AtomicInteger()
            val results =
                downloads
                    .mapIndexed { index, download ->
                        async {
                            permits.withPermit { download.store.obtain(download.artifact, download.suffix) }.also {
                                onProgress(index, done.incrementAndGet(), downloads.size)
                            }
                        }
                    }.awaitAll()
            val failures = results.mapNotNull { (it as? Outcome.Err)?.error }
            if (failures.isEmpty()) {
                Outcome.Ok(results.map { (it as Outcome.Ok).value })
            } else {
                Outcome.Err(DownloadFailures(failures, downloads.size))
            }
        }
}
