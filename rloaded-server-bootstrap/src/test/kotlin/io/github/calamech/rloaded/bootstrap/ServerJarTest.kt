package io.github.calamech.rloaded.bootstrap

import org.junit.jupiter.api.Test
import java.util.zip.ZipFile
import kotlin.test.assertEquals

class ServerJarTest {
    private val jar = System.getProperty("rloaded.serverJar") ?: error("run with the rloaded.serverJar property")

    @Test
    fun `keeps the notice of each bundled Apache Commons library`() {
        val firstLines =
            ZipFile(jar).use { zip ->
                NOTICES.keys.associateWith { module ->
                    zip.getEntry("META-INF/licenses/$module/NOTICE.txt")?.let { entry ->
                        zip.getInputStream(entry).bufferedReader().readLine()
                    }
                }
            }

        assertEquals(NOTICES, firstLines)
    }

    private companion object {
        val NOTICES =
            mapOf(
                "commons-codec/commons-codec" to "Apache Commons Codec",
                "commons-io/commons-io" to "Apache Commons IO",
                "org.apache.commons/commons-compress" to "Apache Commons Compress",
                "org.apache.commons/commons-lang3" to "Apache Commons Lang",
            )
    }
}
