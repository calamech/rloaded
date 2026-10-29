package io.github.calamech.rloaded.conventions

import io.github.calamech.rloaded.conventions.ConventionFixture.Companion.JAVA_21_CLASS_FILE_VERSION
import io.github.calamech.rloaded.conventions.ConventionFixture.Companion.assertOutput
import io.github.calamech.rloaded.conventions.ConventionFixture.Companion.classFileVersion
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * Builds a small library with the rloaded.kotlin-library convention and checks that each gate
 * passes on clean code and fails on the defect it exists for, with the expected message.
 *
 * One fixture is shared by all tests so build-logic is compiled once; each test rewrites the
 * library sources before running.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KotlinLibraryConventionTest {
    private lateinit var fixture: ConventionFixture

    @BeforeAll
    fun createFixture(
        @TempDir dir: File,
    ) {
        fixture = ConventionFixture(dir, "rloaded.kotlin-library")
    }

    @Test
    fun `passes on clean code`() {
        library(main = GREETER, test = GREETER_TEST)

        val result = run().build()

        for (task in listOf(":lib:spotlessCheck", ":lib:detekt", ":lib:test", ":lib:koverVerify")) {
            assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome, task)
        }
        assertEquals(JAVA_21_CLASS_FILE_VERSION, classFileVersion(fixture.output("classes/kotlin/main/fixture/Greeter.class")))
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
        fixture.sources(mapOf("main/kotlin/fixture/Greeter.kt" to main, "test/kotlin/fixture/GreeterTest.kt" to test))
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

    private fun run() = fixture.run()

    private companion object {
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
