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

// Test mods: each is a mod of its own, built into its own jar from its own source set. The main build
// asks for a jar by its capability, io.github.calamech.rloaded:rloaded-runtime-<name>.
fun testMod(
    name: String,
    seesRuntime: Boolean,
) {
    val main = sourceSets.main.get()
    val sourceSet =
        sourceSets.create(name) {
            compileClasspath += main.compileClasspath
            runtimeClasspath += main.runtimeClasspath
            if (seesRuntime) {
                compileClasspath += main.output
                runtimeClasspath += main.output
            }
        }
    tasks.named<ProcessResources>(sourceSet.processResourcesTaskName) {
        val version = project.version
        inputs.property("version", version)
        filesMatching("fabric.mod.json") { expand("version" to version) }
    }
    val jar =
        tasks.register<Jar>("${name}Jar") {
            description = "Assembles the $name test mod."
            archiveClassifier = name
            from(sourceSet.output)
        }
    tasks.assemble {
        dependsOn(jar)
    }
    val capability = name.replace(Regex("([A-Z])")) { "-" + it.value.lowercase() }
    configurations.consumable("${name}Elements") {
        outgoing {
            capability("io.github.calamech.rloaded:rloaded-runtime-$capability:$version")
            artifact(jar)
        }
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        }
    }
}

// The game test mod sees the runtime mod's classes; the mods it loads while the game runs see only
// Minecraft and Fabric, like mods from Modrinth.
testMod("gametest", seesRuntime = true)
testMod("contentBasic", seesRuntime = false)
testMod("mixinLoadedClass", seesRuntime = false)
