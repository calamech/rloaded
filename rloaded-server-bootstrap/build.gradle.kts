plugins {
    id("rloaded.kotlin-library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val agent = configurations.dependencyScope("agent")
val agentJar = configurations.resolvable("agentJar") { extendsFrom(agent.get()) }

dependencies {
    implementation(project(":rloaded-core"))
    implementation(libs.ktoml.core)
    // Ktor logs through SLF4J; the bootstrap reports failures itself, so its logs are dropped.
    runtimeOnly(libs.slf4j.nop)
    agent(project(":rloaded-agent"))

    testImplementation(testFixtures(project(":rloaded-core")))
}

// The bootstrap carries the agent jar and writes it next to the server at startup.
tasks.processResources {
    from(agentJar) {
        into("io/github/calamech/rloaded/bootstrap")
        rename { "rloaded-agent.jar" }
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
        }
    }

tasks.assemble {
    dependsOn(serverJar)
}
