package io.github.calamech.rloaded.core.download

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlPolicyTest {
    @Test
    fun `allows production origins over HTTPS only`() {
        assertNull(UrlPolicy.PRODUCTION.refusalReason("https://piston-data.mojang.com/v1/objects/abc/client.jar"))
        assertNull(UrlPolicy.PRODUCTION.refusalReason("https://CDN.modrinth.com:443/data/abc/file.jar"))
        assertEquals(
            "http://cdn.modrinth.com:80 is not an allowed origin",
            UrlPolicy.PRODUCTION.refusalReason("http://cdn.modrinth.com/data/abc/file.jar"),
        )
        assertEquals(
            "https://evil.example:443 is not an allowed origin",
            UrlPolicy.PRODUCTION.refusalReason("https://evil.example/file.jar"),
        )
    }

    @Test
    fun `refuses URLs it cannot read`() {
        assertEquals("not a valid URL", UrlPolicy.PRODUCTION.refusalReason("https://bad host/"))
        assertEquals("no scheme", UrlPolicy.PRODUCTION.refusalReason("/relative"))
        assertEquals("no host", UrlPolicy.PRODUCTION.refusalReason("file:///etc/passwd"))
    }
}
