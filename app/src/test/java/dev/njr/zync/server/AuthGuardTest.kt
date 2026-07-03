package dev.njr.zync.server

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure, Ktor-free core of the request guard ([AuthGuard]). Kept separate from
 * any `testApplication` so the loopback-vs-LAN decision logic can be exercised directly without
 * relying on how a particular test harness happens to simulate connector schemes.
 */
class AuthGuardTest {

    @Test
    fun `classifies scheme https as LAN and everything else as loopback`() {
        assertEquals(AuthGuard.Connector.LAN, AuthGuard.classify("https"))
        assertEquals(AuthGuard.Connector.LAN, AuthGuard.classify("HTTPS"))
        assertEquals(AuthGuard.Connector.LOOPBACK, AuthGuard.classify("http"))
        assertEquals(AuthGuard.Connector.LOOPBACK, AuthGuard.classify("ws"))
    }

    @Test
    fun `pairing paths are recognized regardless of leading slash`() {
        assertTrue(AuthGuard.isPairingPath("/pair/request"))
        assertTrue(AuthGuard.isPairingPath("pair/challenge"))
        assertFalse(AuthGuard.isPairingPath("/api/roots"))
        assertFalse(AuthGuard.isPairingPath("/"))
    }

    @Test
    fun `document paths are exactly root and index html`() {
        assertTrue(AuthGuard.isDocumentPath("/"))
        assertTrue(AuthGuard.isDocumentPath("/index.html"))
        assertFalse(AuthGuard.isDocumentPath("/api/roots"))
        assertFalse(AuthGuard.isDocumentPath("/js/app.js"))
    }

    @Test
    fun `loopback connector accepts the loopback token`() = runTest {
        val ok = AuthGuard.isAuthorized(
            connector = AuthGuard.Connector.LOOPBACK,
            loopbackToken = "secret",
            presentedLoopbackToken = "secret",
            sessionToken = null,
            isValidSession = { false },
        )
        assertTrue(ok)
    }

    @Test
    fun `loopback connector rejects a wrong loopback token with no session`() = runTest {
        val ok = AuthGuard.isAuthorized(
            connector = AuthGuard.Connector.LOOPBACK,
            loopbackToken = "secret",
            presentedLoopbackToken = "wrong",
            sessionToken = null,
            isValidSession = { false },
        )
        assertFalse(ok)
    }

    @Test
    fun `loopback connector also accepts a valid session token`() = runTest {
        val ok = AuthGuard.isAuthorized(
            connector = AuthGuard.Connector.LOOPBACK,
            loopbackToken = "secret",
            presentedLoopbackToken = null,
            sessionToken = "session-token",
            isValidSession = { it == "session-token" },
        )
        assertTrue(ok)
    }

    @Test
    fun `LAN connector rejects the loopback token even if correct`() = runTest {
        val ok = AuthGuard.isAuthorized(
            connector = AuthGuard.Connector.LAN,
            loopbackToken = "secret",
            presentedLoopbackToken = "secret",
            sessionToken = null,
            isValidSession = { false },
        )
        assertFalse(ok)
    }

    @Test
    fun `LAN connector accepts a valid session token`() = runTest {
        val ok = AuthGuard.isAuthorized(
            connector = AuthGuard.Connector.LAN,
            loopbackToken = "secret",
            presentedLoopbackToken = null,
            sessionToken = "session-token",
            isValidSession = { it == "session-token" },
        )
        assertTrue(ok)
    }

    @Test
    fun `LAN connector rejects an invalid session token`() = runTest {
        val ok = AuthGuard.isAuthorized(
            connector = AuthGuard.Connector.LAN,
            loopbackToken = "secret",
            presentedLoopbackToken = null,
            sessionToken = "garbage",
            isValidSession = { it == "session-token" },
        )
        assertFalse(ok)
    }

    @Test
    fun `LAN connector rejects a missing session token`() = runTest {
        val ok = AuthGuard.isAuthorized(
            connector = AuthGuard.Connector.LAN,
            loopbackToken = "secret",
            presentedLoopbackToken = null,
            sessionToken = null,
            isValidSession = { true },
        )
        assertFalse(ok)
    }
}
