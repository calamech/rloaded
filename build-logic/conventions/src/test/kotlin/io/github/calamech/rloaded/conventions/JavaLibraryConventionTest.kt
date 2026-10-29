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

/** Checks that each gate of rloaded.java-library passes on clean code and fails on its defect. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JavaLibraryConventionTest {
    private lateinit var fixture: ConventionFixture

    @BeforeAll
    fun createFixture(
        @TempDir dir: File,
    ) {
        fixture = ConventionFixture(dir, "rloaded.java-library")
    }

    @Test
    fun `passes on clean code`() {
        library(main = GREETER)

        val result = fixture.run().build()

        for (task in listOf(":lib:spotlessCheck", ":lib:compileJava", ":lib:test", ":lib:jacocoTestCoverageVerification")) {
            assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome, task)
        }
        assertEquals(JAVA_21_CLASS_FILE_VERSION, classFileVersion(fixture.output("classes/java/main/fixture/Greeter.class")))
    }

    @Test
    fun `fails on badly formatted code`() {
        library(main = GREETER.replace("static String greet(", "static  String greet ("))

        fixture.run().buildAndFail().assertOutput("The following files had format violations")
    }

    @Test
    fun `fails on an Error Prone error`() {
        library(main = GREETER.replace(RETURN, "    new IllegalStateException(name);\n$RETURN"))

        fixture.run().buildAndFail().assertOutput("[DeadException]")
    }

    @Test
    fun `fails on a compiler warning`() {
        library(main = GREETER.replace(RETURN, "    java.util.List raw = java.util.List.of(name);\n    return \"Hello, \" + raw.get(0);"))

        fixture.run().buildAndFail().assertOutput("warnings found and -Werror specified")
    }

    @Test
    fun `fails below 85 percent line coverage`() {
        library(
            main =
                GREETER.replace(
                    "\n}\n",
                    "\n\n  static int untested(int a) {\n    int b = a + 1;\n    int c = b * 2;\n    int d = c - 1;\n    return d;\n  }\n}\n",
                ),
        )

        fixture.run().buildAndFail().assertOutput("lines covered ratio is")
    }

    private fun library(main: String) {
        fixture.sources(mapOf("main/java/fixture/Greeter.java" to main, "test/java/fixture/GreeterTest.java" to GREETER_TEST))
    }

    private companion object {
        const val RETURN = "    return \"Hello, \" + name;"

        val GREETER =
            """
            package fixture;

            /** Greets people. */
            public final class Greeter {
              private Greeter() {}

              /**
               * Returns a greeting.
               *
               * @param name who to greet
               * @return the greeting
               */
              public static String greet(String name) {
                return "Hello, " + name;
              }
            }

            """.trimIndent()

        val GREETER_TEST =
            """
            package fixture;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import org.junit.jupiter.api.Test;

            class GreeterTest {
              @Test
              void greets() {
                assertEquals("Hello, Ada", Greeter.greet("Ada"));
              }
            }

            """.trimIndent()
    }
}
