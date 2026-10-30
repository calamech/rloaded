package io.github.calamech.rloaded.conventions

import io.github.calamech.rloaded.conventions.ConventionFixture.Companion.assertOutput
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * Checks that rloaded.java-quality passes on clean code and fails on each defect in a source set
 * that no test uses, as the runtime mod's test mods are.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JavaQualityConventionTest {
    private lateinit var fixture: ConventionFixture

    @BeforeAll
    fun createFixture(
        @TempDir dir: File,
    ) {
        fixture =
            ConventionFixture(
                dir,
                "rloaded.java-quality",
                """
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(25)
                        vendor = JvmVendorSpec.JETBRAINS
                    }
                }
                sourceSets.create("extra")
                """.trimIndent(),
            )
    }

    @Test
    fun `passes on clean code`() {
        fixture.sources(mapOf(EXTRA to GREETER))

        val result = fixture.run().build()

        for (task in listOf(":lib:spotlessCheck", ":lib:compileExtraJava")) {
            assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome, task)
        }
    }

    @Test
    fun `fails on badly formatted code`() {
        fixture.sources(mapOf(EXTRA to GREETER.replace("static String greet(", "static  String greet (")))

        fixture.run().buildAndFail().assertOutput("The following files had format violations")
    }

    @Test
    fun `fails on an Error Prone error`() {
        fixture.sources(mapOf(EXTRA to GREETER.replace(RETURN, "    new IllegalStateException(name);\n$RETURN")))

        fixture.run().buildAndFail().assertOutput("[DeadException]")
    }

    @Test
    fun `fails on an Error Prone warning`() {
        fixture.sources(mapOf(EXTRA to GREETER.replace(RETURN, "    return \"Hello, \" + (name == new String(name));")))

        fixture.run().buildAndFail().assertOutput("[ReferenceEquality]")
    }

    private companion object {
        const val EXTRA = "extra/java/fixture/Greeter.java"
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
    }
}
