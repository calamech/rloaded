package io.github.calamech.rloaded.modrinth

/**
 * Answers of `api.modrinth.com/v2/search` for Fabric mods of 26.3, recorded on 2026-10-31 and kept
 * unchanged: `sodium`, the 3 most relevant hits.
 */
object SearchRecordings {
    fun of(query: String): String =
        SearchRecordings::class.java.getResource("search-$query.json")?.readText()
            ?: error("no recording of a search for $query")
}
