package io.github.calamech.rloaded.protocol

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LinkMessagesTest {
    private val token = "ab".repeat(32)

    @Test
    fun `round-trips every message`() {
        val messages =
            listOf(
                LinkRequest.Add("r-1", "content-basic", null),
                LinkRequest.Add("r-2", "AANobbMI", "OihdIimA"),
            )
        messages.forEach { request ->
            assertEquals(
                Outcome.Ok(request),
                ProtocolCodec.decode(LinkRequest.serializer(), ProtocolCodec.encode(LinkRequest.serializer(), request)),
            )
        }
        listOf(LinkEvent.Added("r-1", listOf("a.jar")), LinkEvent.Refused("r-1", AddProblem.UNSUITABLE, "too new")).forEach { event ->
            assertEquals(
                Outcome.Ok(event),
                ProtocolCodec.decode(LinkEvent.serializer(), ProtocolCodec.encode(LinkEvent.serializer(), event)),
            )
        }
        val hello = LinkHello(LINK_PROTOCOL_VERSION, token)
        assertEquals(Outcome.Ok(hello), ProtocolCodec.decode(LinkHello.serializer(), ProtocolCodec.encode(LinkHello.serializer(), hello)))
    }

    @Test
    fun `refuses fields out of their bounds`() {
        assertEquals("token must be 64 lowercase hexadecimal digits", refusal { LinkHello(1, "AB".repeat(32)) })
        assertEquals("protocol must be at least 1", refusal { LinkHello(0, token) })
        assertEquals("requestId must be 1 to 64 letters, digits or dashes", refusal { LinkRequest.Add("r 1", "p", null) })
        assertEquals("projectId must be 1 to 64 letters, digits, dashes or underscores", refusal { LinkRequest.Add("r", "a/b", null) })
        assertEquals("versionId must be 1 to 16 letters or digits", refusal { LinkRequest.Add("r", "p", "v-1") })
        assertEquals("detail must have 1 to 16384 characters", refusal { LinkEvent.Refused("r", AddProblem.BUSY, "") })
        assertEquals("files must be jar file names without a path", refusal { LinkEvent.Added("r", listOf("../a.jar")) })
        assertEquals("at most 1024 files", refusal { LinkEvent.Added("r", List(1025) { "a.jar" }) })
    }

    private fun refusal(block: () -> Any): String? = runCatching(block).exceptionOrNull()?.message
}
