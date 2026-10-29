plugins {
    id("rloaded.java-library")
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "io.github.calamech.rloaded.agent.RloadedAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
        )
    }
}

// The agent tests start a separate JVM with the built jar.
tasks.test {
    val agentJar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(agentJar).withPropertyName("agentJar").withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Drloaded.agentJar=${agentJar.get().asFile.absolutePath}") })
}
