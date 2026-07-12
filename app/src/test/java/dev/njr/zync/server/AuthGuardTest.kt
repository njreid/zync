package dev.njr.zync.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure, Ktor-free core of the loopback request guard ([AuthGuard]). Kept
 * separate from any `testApplication` so the token decision logic can be exercised directly.
 */
class AuthGuardTest {

    @Test
    fun `document paths are exactly root and index html`() {
        assertTrue(AuthGuard.isDocumentPath("/"))
        assertTrue(AuthGuard.isDocumentPath("/index.html"))
        assertFalse(AuthGuard.isDocumentPath("/api/roots"))
        assertFalse(AuthGuard.isDocumentPath("/js/app.js"))
    }

    @Test
    fun `accepts the matching loopback token`() {
        assertTrue(AuthGuard.isAuthorized(loopbackToken = "secret", presentedLoopbackToken = "secret"))
    }

    @Test
    fun `rejects a wrong loopback token`() {
        assertFalse(AuthGuard.isAuthorized(loopbackToken = "secret", presentedLoopbackToken = "wrong"))
    }

    @Test
    fun `rejects a missing loopback token`() {
        assertFalse(AuthGuard.isAuthorized(loopbackToken = "secret", presentedLoopbackToken = null))
    }

    @Test
    fun `constantTimeEquals matches equal strings and rejects unequal`() {
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abc", "abd"))
        assertFalse(constantTimeEquals("abc", "abcd"))
    }
}
