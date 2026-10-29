plugins {
    alias(libs.plugins.fabric.loom)
}

group = "io.github.calamech.rloaded"
version = "0.1.0"

// Minecraft 26.3 needs Java 25.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.JETBRAINS
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

dependencies {
    minecraft("com.mojang:minecraft:${libs.versions.minecraft.get()}")
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") { expand("version" to version) }
}

// Game tests: a second mod with test code, built into its own jar and run on a game test server.
val gametest =
    sourceSets.create("gametest") {
        compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
    }

tasks.named<ProcessResources>("processGametestResources") {
    val version = project.version
    inputs.property("version", version)
    filesMatching("fabric.mod.json") { expand("version" to version) }
}

val gametestJar =
    tasks.register<Jar>("gametestJar") {
        description = "Assembles the game test mod."
        archiveClassifier = "gametest"
        from(gametest.output)
    }

tasks.assemble {
    dependsOn(gametestJar)
}

// Lets the main build run the game test mod: it asks for this capability.
configurations.consumable("gametestElements") {
    outgoing {
        capability("io.github.calamech.rloaded:rloaded-runtime-gametest:$version")
        artifact(gametestJar)
    }
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
