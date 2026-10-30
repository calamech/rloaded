package io.github.calamech.rloaded.modrinth

/**
 * Answers of `api.modrinth.com/v2/project/<project>/version` for Fabric and 26.3, recorded on
 * 2026-10-31 and kept unchanged: Mod Menu (`modmenu`, a release and a beta, needing Text Placeholder
 * API and Fabric API), Text Placeholder API (`eXts2L7r`) and Fabric API (`P7dR8mSH`).
 */
object ProjectVersionRecordings {
    fun of(project: String): String =
        ProjectVersionRecordings::class.java.getResource("project-versions-$project.json")?.readText()
            ?: error("no recording of $project")
}
