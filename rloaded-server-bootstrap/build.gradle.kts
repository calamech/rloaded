plugins {
    id("rloaded.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val agent = configurations.dependencyScope("agent")
val agentJar = configurations.resolvable("agentJar") { extendsFrom(agent.get()) }
val runtimeMod = configurations.dependencyScope("runtimeMod")
val runtimeModJar = configurations.resolvable("runtimeModJar") { extendsFrom(runtimeMod.get()) }

dependencies {
    implementation(project(":rloaded-core"))
    implementation(project(":rloaded-admin"))
    implementation(libs.ktoml.core)
    // Ktor logs through SLF4J; the bootstrap reports failures itself, so its logs are dropped.
    runtimeOnly(libs.slf4j.nop)
    agent(project(":rloaded-agent"))
    runtimeMod("io.github.calamech.rloaded:rloaded-runtime") { isTransitive = false }

    testImplementation(testFixtures(project(":rloaded-core")))
}

// The bootstrap carries the agent and the runtime mod, and writes them next to the server at startup.
tasks.processResources {
    from(agentJar) {
        into("io/github/calamech/rloaded/bootstrap")
        rename { "rloaded-agent.jar" }
    }
    from(runtimeModJar) {
        into("io/github/calamech/rloaded/bootstrap")
        rename { "rloaded-runtime.jar" }
    }
}

// rloaded-server.jar: the bootstrap and its dependencies in one jar, started with java -jar.
val serverJar =
    tasks.register<Jar>("serverJar") {
        description = "Assembles rloaded-server.jar with every dependency inside."
        group = LifecycleBasePlugin.BUILD_GROUP
        archiveFileName = "rloaded-server.jar"
        manifest { attributes("Main-Class" to "io.github.calamech.rloaded.bootstrap.MainKt") }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(sourceSets.main.map { it.output })
        from(configurations.runtimeClasspath.map { classpath -> classpath.map { zipTree(it) } }) {
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
            exclude("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
        // Dependencies name their license and notice files alike, so each keeps them under its own path.
        includeEmptyDirs = false
        configurations.runtimeClasspath
            .get()
            .incoming.artifacts.resolvedArtifacts
            .get()
            .forEach { artifact ->
                val module = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@forEach
                val directory = "META-INF/licenses/${module.group}/${module.module}"
                from(zipTree(artifact.file)) {
                    include("META-INF/LICENSE*", "META-INF/NOTICE*")
                    eachFile { path = "$directory/$name" }
                }
            }
    }

tasks.assemble {
    dependsOn(serverJar)
}

// ServerJarTest reads the built jar.
tasks.test {
    val jar = serverJar.flatMap { it.archiveFile }
    inputs.file(jar).withPropertyName("serverJar")
    systemProperty("rloaded.serverJar", jar.get().asFile.absolutePath)
}
