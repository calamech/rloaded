import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.fabric.loom)
    id("rloaded.java-quality")
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

// The game's libraries (Guava, Log4j) carry annotations whose classes the game does not ship. Once
// Error Prone reads those annotations, javac warns about the missing classes in jars that are not
// ours, so that one category of warning is off.
tasks.withType<JavaCompile>().configureEach {
    options.release = 25
    options.compilerArgs.add("-Xlint:-classfile")
}

dependencies {
    minecraft("com.mojang:minecraft:${libs.versions.minecraft.get()}")
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)
}

// Kotlin libraries the mod uses: Rloaded's own modules and what they need, Ktor first. They are
// nested in the mod jar, each wrapped as a mod, which is how Fabric loads libraries from a mod.
// Fabric Language Kotlin, in the base set, provides the Kotlin standard library and kotlinx in the
// versions the main build uses (ADR 0019), and Minecraft provides SLF4J: the mod compiles against
// them and leaves them out. Loom resolves its own configurations while configuring, before the main
// build's projects can be substituted, so these go through configurations Loom does not know.
val libraries = configurations.dependencyScope("libraries")
val libraryClasspath =
    configurations.resolvable("libraryClasspath") {
        extendsFrom(libraries.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        }
    }

dependencies {
    libraries("io.github.calamech.rloaded:rloaded-admin")
}

// One line per nested library: its mod id, its version and its file name. Built in a function so
// the lambdas capture its locals only, which the configuration cache requires.
fun nestedLibraryLines(
    classpath: NamedDomainObjectProvider<ResolvableConfiguration>,
    projectVersion: String,
): Provider<List<String>> {
    val providedGroups = setOf("org.jetbrains", "org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.slf4j")
    val nestedAnyway = setOf("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")
    return classpath.flatMap { configuration ->
        configuration.incoming
            .artifactView {
                componentFilter { id ->
                    when (id) {
                        is ProjectComponentIdentifier -> true
                        is ModuleComponentIdentifier -> "${id.group}:${id.module}" in nestedAnyway || id.group !in providedGroups
                        else -> false
                    }
                }
            }.artifacts.resolvedArtifacts
            .map { artifacts ->
                artifacts
                    .map { artifact ->
                        val (id, libraryVersion) =
                            when (val component = artifact.id.componentIdentifier) {
                                is ProjectComponentIdentifier -> {
                                    "io_github_calamech_rloaded_${component.projectName}" to projectVersion
                                }

                                is ModuleComponentIdentifier -> {
                                    "${component.group.replace('.', '_')}_${component.module}" to
                                        component.version
                                }

                                else -> {
                                    error("unexpected library ${artifact.id}")
                                }
                            }
                        listOf(id, libraryVersion, artifact.file.name).joinToString("\t")
                    }.sorted()
            }
    }
}

val nestedLibraries = nestedLibraryLines(libraryClasspath, version.toString())

// Copies each library jar with a generated fabric.mod.json, so Fabric loads it as a mod.
val nestLibraries =
    tasks.register("nestLibraries") {
        description = "Wraps the Kotlin libraries the mod nests."
        val lines = nestedLibraries
        val jars = files(libraryClasspath)
        val output = layout.buildDirectory.dir("nested-libraries")
        inputs.property("libraries", lines)
        inputs.files(jars).withPathSensitivity(PathSensitivity.NAME_ONLY)
        outputs.dir(output)
        doLast {
            val directory = output.get().asFile.resolve("META-INF/jars")
            directory.deleteRecursively()
            directory.mkdirs()
            val byName = jars.files.associateBy { it.name }
            for (line in lines.get()) {
                val (id, libraryVersion, fileName) = line.split("\t")
                ZipInputStream(byName.getValue(fileName).inputStream()).use { input ->
                    ZipOutputStream(directory.resolve("$id-$libraryVersion.jar").outputStream()).use { zip ->
                        while (true) {
                            val entry = input.nextEntry ?: break
                            if (entry.name == "fabric.mod.json") continue
                            zip.putNextEntry(ZipEntry(entry.name))
                            input.copyTo(zip)
                            zip.closeEntry()
                        }
                        zip.putNextEntry(ZipEntry("fabric.mod.json"))
                        val metadata =
                            """{"schemaVersion": 1, "id": "$id", "version": "$libraryVersion", "name": "$id", """ +
                                """"custom": {"fabric-loom:generated": true}}"""
                        zip.write(metadata.toByteArray())
                        zip.closeEntry()
                    }
                }
            }
        }
    }

tasks.processResources {
    // The compatibility database of this release, read by servers at start.
    from("../compat") {
        include("compat.json")
        into("io/github/calamech/rloaded/runtime/admin")
    }
    val version = project.version
    val jars =
        nestedLibraries.map { lines ->
            lines.joinToString(", ") { line ->
                val (id, libraryVersion) = line.split("\t")
                """{"file": "META-INF/jars/$id-$libraryVersion.jar"}"""
            }
        }
    inputs.property("version", version)
    inputs.property("jars", jars)
    filesMatching("fabric.mod.json") { expand("version" to version, "jars" to jars.get()) }
}

tasks.jar {
    from(nestLibraries)
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
testMod("mixinFieldInit", seesRuntime = false)
testMod("keybindAndTab", seesRuntime = false)
testMod("lateItem", seesRuntime = false)
testMod("joinTest", seesRuntime = false)
testMod("throwsOnInit", seesRuntime = false)
testMod("failsOnClientOnce", seesRuntime = false)
testMod("failsInRunningServer", seesRuntime = false)
testMod("addTest", seesRuntime = true)
testMod("singleplayerTest", seesRuntime = true)

// After the test mods: they compile against Minecraft and Fabric only, while the game test mod
// calls the libraries the runtime mod nests.
sourceSets.main {
    compileClasspath += files(libraryClasspath)
}
sourceSets.named("gametest") {
    compileClasspath += files(libraryClasspath)
}
