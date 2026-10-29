package io.github.calamech.rloaded.conventions

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Builds a small library with the rloaded.kotlin-library convention and checks that each gate
 * passes on clean code and fails on the defect it exists for, with the expected message.
 *
 * One fixture is shared by all tests so build-logic is compiled once; each test rewrites the
 * library sources before running.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KotlinLibraryConventionTest {
    private lateinit var root: File

    @BeforeAll
    fun createFixture(
        @TempDir dir: File,
    ) {
        root = dir
        val repository = File(System.getProperty("rloaded.repositoryDir"))
        repository.resolve("build-logic").copyRecursively(root.resolve("build-logic"))
        root
            .resolve("build-logic")
            .walkTopDown()
            .filter { it.isDirectory && it.name in BUILD_OUTPUTS }
            .forEach { it.deleteRecursively() }
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
                id("rloaded.kotlin-library")
            }

            """.trimIndent(),
        )
    }

    @Test
    fun `passes on clean code`() {
        library(main = GREETER, test = GREETER_TEST)

        val result = run().build()

        for (task in listOf(":lib:spotlessCheck", ":lib:detekt", ":lib:test", ":lib:koverVerify")) {
            assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome, task)
        }
    }

    @Test
    fun `fails on badly formatted code`() {
        library(main = GREETER.replace("public fun greet(", "public  fun greet ("), test = GREETER_TEST)

        run().buildAndFail().assertOutput("The following files had format violations")
    }

    @Test
    fun `fails on a not-null assertion in main code`() {
        library(
            main =
                GREETER + "\n" +
                    """
                    /** Returns the length of [value]. */
                    public fun length(value: String?): Int = value!!.length

                    """.trimIndent(),
            test = GREETER_TEST + testOf("length", """assertEquals(1, length("a"))"""),
        )

        run().buildAndFail().assertOutput("Avoid !!; handle the null case explicitly. [NoNotNullAssertion]")
    }

    @Test
    fun `allows a not-null assertion in tests`() {
        library(
            main = GREETER,
            test = GREETER_TEST + testOf("bang", """assertEquals(1, listOf<String?>("a").first()!!.length)"""),
        )

        assertEquals(TaskOutcome.SUCCESS, run().build().task(":lib:detekt")?.outcome)
    }

    @Test
    fun `fails on a default detekt rule`() {
        library(
            main =
                GREETER + "\n" +
                    """
                    /** Returns the timeout. */
                    public fun timeoutMillis(seconds: Int): Int = seconds * 4242

                    """.trimIndent(),
            test = GREETER_TEST + testOf("timeout", "assertEquals(4242, timeoutMillis(1))"),
        )

        run().buildAndFail().assertOutput("[MagicNumber]")
    }

    @Test
    fun `fails below 85 percent line coverage`() {
        library(
            main =
                GREETER + "\n" +
                    """
                    /** Not tested. */
                    public fun untested(a: Int): Int {
                        val b = a + 1
                        val c = b * 2
                        val d = c - 1
                        return d
                    }

                    """.trimIndent(),
            test = GREETER_TEST,
        )

        run().buildAndFail().assertOutput("lines covered percentage is")
    }

    @Test
    fun `fails on a declaration without explicit visibility`() {
        library(main = GREETER.replace("public object Greeter", "object Greeter"), test = GREETER_TEST)

        run().buildAndFail().assertOutput("Visibility must be specified in explicit API mode")
    }

    @Test
    fun `fails on a compiler warning`() {
        library(main = GREETER.replace("\"Hello, \$name\"", "\"Hello, \$name\" as String"), test = GREETER_TEST)

        run().buildAndFail().assertOutput("warnings found and -Werror specified")
    }

    private fun library(
        main: String,
        test: String,
    ) {
        val sources = root.resolve("lib/src")
        sources.deleteRecursively()
        sources.resolve("main/kotlin/fixture/Greeter.kt").also { it.parentFile.mkdirs() }.writeText(main)
        sources.resolve("test/kotlin/fixture/GreeterTest.kt").also { it.parentFile.mkdirs() }.writeText(test)
    }

    private fun testOf(
        name: String,
        body: String,
    ): String =
        """

        class ${name.replaceFirstChar { it.uppercase() }}Test {
            @Test
            fun $name() {
                $body
            }
        }

        """.trimIndent()

    private fun run(): GradleRunner =
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

    private fun BuildResult.assertOutput(expected: String) {
        assertTrue(expected in output, "Expected the output to contain '$expected'. Output:\n$output")
    }

    private companion object {
        val BUILD_OUTPUTS = setOf("build", ".gradle", ".kotlin")

        val GREETER =
            """
            package fixture

            /** Greets people. */
            public object Greeter {
                /** Returns a greeting for [name]. */
                public fun greet(name: String): String = "Hello, ${'$'}name"
            }

            """.trimIndent()

        val GREETER_TEST =
            """
            package fixture

            import kotlin.test.Test
            import kotlin.test.assertEquals

            class GreeterTest {
                @Test
                fun greets() {
                    assertEquals("Hello, Ada", Greeter.greet("Ada"))
                }
            }

            """.trimIndent()
    }
}
