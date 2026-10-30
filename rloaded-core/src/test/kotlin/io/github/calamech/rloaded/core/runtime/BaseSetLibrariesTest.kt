package io.github.calamech.rloaded.core.runtime

import kotlinx.coroutines.Job
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// Kotlin code that runs in the game uses the libraries Fabric Language Kotlin ships, so the build
// compiles against the same versions. The table was read from the pinned jar's META-INF/jars.
class BaseSetLibrariesTest {
    @Test
    fun `pins the Fabric Language Kotlin whose libraries are listed here`() {
        val pinned =
            RuntimePins.CURRENT.baseSet
                .map { it.fileName }
                .filter { it.startsWith("fabric-language-kotlin-") }

        assertEquals(listOf("fabric-language-kotlin-1.14.2+kotlin.2.4.21.jar"), pinned)
    }

    @Test
    fun `compiles against the library versions Fabric Language Kotlin ships`() {
        val used =
            mapOf(
                "kotlin-stdlib" to KotlinVersion.CURRENT.toString(),
                "kotlinx-coroutines-core" to
                    Job::class.java.classLoader
                        .getResource("META-INF/kotlinx_coroutines_core.version")
                        ?.readText()
                        ?.trim(),
                "kotlinx-serialization-core" to KSerializer::class.java.`package`.implementationVersion,
                "kotlinx-serialization-json" to Json::class.java.`package`.implementationVersion,
            )

        assertEquals(SHIPPED, used)
    }

    private companion object {
        val SHIPPED =
            mapOf(
                "kotlin-stdlib" to "2.4.21",
                "kotlinx-coroutines-core" to "1.11.0",
                "kotlinx-serialization-core" to "1.11.0",
                "kotlinx-serialization-json" to "1.11.0",
            )
    }
}
