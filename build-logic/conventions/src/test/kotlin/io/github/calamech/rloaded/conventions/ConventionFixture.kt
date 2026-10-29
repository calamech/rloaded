package io.github.calamech.rloaded.conventions

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import kotlin.test.assertTrue

/**
 * A copy of the repository's build-logic, version catalog and detekt configuration, plus one
 * library module `:lib` that applies [plugin]. Builds run with the JDKs of the outer build.
 */
class ConventionFixture(
    private val root: File,
    plugin: String,
) {
    init {
        val repository = File(System.getProperty("rloaded.repositoryDir"))
        // Sources only: build outputs may be locked by the running build, on Windows in particular.
        val buildLogic = repository.resolve("build-logic")
        buildLogic
            .walkTopDown()
            .onEnter { it.name !in BUILD_OUTPUTS }
            .filter { it.isFile }
            .forEach { it.copyTo(root.resolve("build-logic").resolve(it.relativeTo(buildLogic))) }
        repository.resolve("gradle/libs.versions.toml").copyTo(root.resolve("gradle/libs.versions.toml"))
        repository.resolve("config/detekt/detekt.yml").copyTo(root.resolve("config/detekt/detekt.yml"))
        root.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                includeBuild("build-logic")
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                }
            }
            includeBuild("build-logic")
            rootProject.name = "fixture"
            include(":lib")
            """.trimIndent(),
        )
        root.resolve("lib/build.gradle.kts").also { it.parentFile.mkdirs() }.writeText(
            """
            plugins {
                id("$plugin")
            }

            """.trimIndent(),
        )
    }

    /** Replaces the sources of `:lib` with [files], keyed by path relative to `lib/src`. */
    fun sources(files: Map<String, String>) {
        val sources = root.resolve("lib/src")
        sources.deleteRecursively()
        for ((path, content) in files) {
            sources.resolve(path).also { it.parentFile.mkdirs() }.writeText(content)
        }
    }

    /** Reads a file produced by the build of `:lib`. */
    fun output(path: String): ByteArray = root.resolve("lib/build").resolve(path).readBytes()

    fun run(): GradleRunner =
        GradleRunner
            .create()
            .withProjectDir(root)
            .withArguments(
                ":lib:check",
                "--configuration-cache",
                "--stacktrace",
                // Command line properties reach every build of the composite, including build-logic.
                "-Porg.gradle.java.installations.auto-detect=false",
                "-Porg.gradle.java.installations.auto-download=false",
                "-Porg.gradle.java.installations.paths=${System.getProperty("rloaded.jdks")}",
            )

    companion object {
        private val BUILD_OUTPUTS = setOf("build", ".gradle", ".kotlin")

        const val JAVA_21_CLASS_FILE_VERSION: Int = 65

        fun classFileVersion(bytes: ByteArray): Int = (bytes[6].toInt() shl 8) or bytes[7].toInt()

        fun BuildResult.assertOutput(expected: String) {
            assertTrue(expected in output, "Expected the output to contain '$expected'. Output:\n$output")
        }
    }
}
