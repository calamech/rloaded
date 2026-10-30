package io.github.calamech.rloaded.gametests

import io.github.calamech.rloaded.protocol.Outcome

/**
 * The raw ids one game process gave its blocks and block states, keyed `block <id>` and
 * `state <description>`, as the registry sync check writes them.
 */
internal data class RegistryIds(
    val ids: Map<String, Int>,
) {
    companion object {
        /** Reads lines of `<kind> <name> <raw id>`; any other line refuses the whole file. */
        fun parse(text: String): Outcome<RegistryIds, String> {
            val lines = text.lines().filter { it.isNotBlank() }
            val parsed =
                lines.map { line ->
                    line.split(' ').takeIf { it.size == FIELDS }?.let { (kind, name, raw) ->
                        raw.toIntOrNull()?.let {
                            "$kind $name" to
                                it
                        }
                    }
                }
            val invalid = lines.zip(parsed).firstOrNull { (_, entry) -> entry == null }
            return when {
                lines.isEmpty() -> Outcome.Err("no raw ids")
                invalid != null -> Outcome.Err("not a raw id line: ${invalid.first}")
                else -> Outcome.Ok(RegistryIds(parsed.filterNotNull().toMap()))
            }
        }

        private const val FIELDS = 3
    }
}

/**
 * Everything wrong with one run of the registry sync check, empty when it passed: the server
 * registered every added block, at least one client gave them other raw ids before the sync, so
 * the run can tell a sync from luck, and every client agrees with the server on every id after it.
 */
internal fun registrySyncProblems(
    server: RegistryIds,
    beforeSync: Map<String, RegistryIds>,
    afterSync: Map<String, RegistryIds>,
    addedBlocks: List<String>,
): List<String> {
    val addedKeys = addedBlocks.map { "block $it" }
    val missing = addedKeys.filter { it !in server.ids }.map { "the server has no $it" }
    val diverged = beforeSync.values.any { client -> addedKeys.any { client.ids[it] != server.ids[it] } }
    val noDivergence = if (diverged) emptyList() else listOf("no client gave the added blocks other raw ids before the sync")
    val disagreements = afterSync.mapNotNull { (client, ids) -> disagreement(client, server, ids) }
    return missing + noDivergence + disagreements
}

private fun disagreement(
    client: String,
    server: RegistryIds,
    ids: RegistryIds,
): String? {
    val keys = (server.ids.keys + ids.ids.keys).filter { server.ids[it] != ids.ids[it] }.sorted()
    return keys.takeIf { it.isNotEmpty() }?.let {
        val shown = it.take(SHOWN_DIFFERENCES).joinToString("; ") { key -> "$key: server ${server.ids[key]}, client ${ids.ids[key]}" }
        "$client disagrees with the server on ${it.size} ids after the sync: $shown"
    }
}

private const val SHOWN_DIFFERENCES = 5
