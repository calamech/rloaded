package io.github.calamech.rloaded.gametests

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

// Each add of the audit log in short, in order: who asked for what, and what came of it.
internal fun auditTrail(audit: Path): List<String> {
    val lines = if (audit.exists()) audit.readText().lines().filter { it.isNotBlank() } else emptyList()
    val requests = lines.filter { field(it, "event") == "requested" }
    return requests.map { request ->
        val who = field(request, "player") ?: field(request, "actor")
        val outcome = lines.firstOrNull { field(it, "add") == field(request, "add") && it != request }
        val result = outcome?.let { field(it, "event") + (field(it, "problem")?.let { problem -> " $problem" } ?: "") }
        "$who ${field(request, "project")}: $result"
    }
}

private fun field(
    line: String,
    key: String,
): String? = Regex(""""$key":"([^"]*)"""").find(line)?.groupValues?.get(1)
