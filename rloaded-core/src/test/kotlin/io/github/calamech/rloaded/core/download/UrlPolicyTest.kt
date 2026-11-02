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
    fun `allows the launcher's releases on GitHub, and nothing of GitHub for game files`() {
        assertNull(UrlPolicy.UPDATES.refusalReason("https://api.github.com/repos/calamech/rloaded/releases/latest"))
        assertNull(UrlPolicy.UPDATES.refusalReason("https://github.com/calamech/rloaded/releases/download/v1.0.0/SHA256SUMS"))
        assertNull(UrlPolicy.UPDATES.refusalReason("https://release-assets.githubusercontent.com/github-production-release-asset/1"))
        assertEquals(
            "https://cdn.modrinth.com:443 is not an allowed origin",
            UrlPolicy.UPDATES.refusalReason("https://cdn.modrinth.com/data/a"),
        )
        assertEquals("https://github.com:443 is not an allowed origin", UrlPolicy.PRODUCTION.refusalReason("https://github.com/x"))
    }

    @Test
    fun `refuses URLs it cannot read`() {
        assertEquals("not a valid URL", UrlPolicy.PRODUCTION.refusalReason("https://bad host/"))
        assertEquals("no scheme", UrlPolicy.PRODUCTION.refusalReason("/relative"))
        assertEquals("no host", UrlPolicy.PRODUCTION.refusalReason("file:///etc/passwd"))
    }
}
