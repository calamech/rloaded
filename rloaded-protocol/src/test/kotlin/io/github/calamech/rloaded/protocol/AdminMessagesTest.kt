package io.github.calamech.rloaded.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AdminMessagesTest {
    @Test
    fun `round-trips every message`() {
        listOf(AdminRequest.Add("r-1", "content-basic", null), AdminRequest.Add("r-2", "AANobbMI", "OihdIimA"), AdminRequest.ListMods)
            .forEach { request ->
                assertEquals(
                    Outcome.Ok(request),
                    ProtocolCodec.decode(AdminRequest.serializer(), ProtocolCodec.encode(AdminRequest.serializer(), request)),
                )
            }
        listOf(
            AdminEvent.Access(true),
            AdminEvent.Mods(listOf("AANobbMI")),
            AdminEvent.Added("r-1", listOf("a.jar")),
            AdminEvent.Refused("r-1", AddProblem.UNSUITABLE, "too new"),
        ).forEach { event ->
            assertEquals(
                Outcome.Ok(event),
                ProtocolCodec.decode(AdminEvent.serializer(), ProtocolCodec.encode(AdminEvent.serializer(), event)),
            )
        }
    }

    @Test
    fun `refuses fields out of their bounds`() {
        assertEquals("requestId must be 1 to 64 letters, digits or dashes", refusal { AdminRequest.Add("r 1", "p", null) })
        assertEquals("projectId must be 1 to 64 letters, digits, dashes or underscores", refusal { AdminRequest.Add("r", "a/b", null) })
        assertEquals("versionId must be 1 to 16 letters or digits", refusal { AdminRequest.Add("r", "p", "v-1") })
        assertEquals("projectIds must be Modrinth ids", refusal { AdminEvent.Mods(listOf("not/an id")) })
        assertEquals("detail must have 1 to 16384 characters", refusal { AdminEvent.Refused("r", AddProblem.BUSY, "") })
        assertEquals("files must be jar file names without a path", refusal { AdminEvent.Added("r", listOf("../a.jar")) })
        assertEquals("at most 1024 files", refusal { AdminEvent.Added("r", List(1025) { "a.jar" }) })
    }

    private fun refusal(block: () -> Any): String? = runCatching(block).exceptionOrNull()?.message
}
