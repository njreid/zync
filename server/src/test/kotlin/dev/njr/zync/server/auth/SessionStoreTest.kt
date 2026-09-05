package dev.njr.zync.server.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionStoreTest {

    @Test
    fun `mint and validate a fresh session`() {
        val store = SessionStore()
        val token = store.mint(0L)
        assertTrue(store.validate(token, 1_000L))
    }

    @Test
    fun `validate returns false and revokes an expired session`() {
        val store = SessionStore(ttlMillis = 100L)
        val token = store.mint(0L)
        assertFalse(store.validate(token, 200L))
        // Once expired-and-removed, it stays invalid even if "time travels back".
        assertFalse(store.validate(token, 0L))
    }

    @Test
    fun `logout revokes a session`() {
        val store = SessionStore()
        val token = store.mint(0L)
        store.logout(token)
        assertFalse(store.validate(token, 1_000L))
    }

    @Test
    fun `store never grows past the LRU cap`() {
        var counter = 0
        val store = SessionStore(tokenGenerator = { "token-${counter++}" })
        repeat(2000) { store.mint(0L) }
        assertTrue(store.size() <= 1024, "session store grew unbounded: size=${store.size()}")
    }

    @Test
    fun `oldest session is evicted once the cap is exceeded`() {
        var counter = 0
        val store = SessionStore(tokenGenerator = { "token-${counter++}" })
        repeat(1024) { store.mint(0L) }
        val firstToken = "token-0"
        assertTrue(store.validate(firstToken, 1L))

        // Push one more in; the LRU should evict the least-recently-used entry. Since
        // `validate` above touched token-0 (access-order LinkedHashMap), the next-oldest
        // untouched entry (token-1) should be the one evicted instead.
        store.mint(1L)
        assertFalse(store.validate("token-1", 2L))
        assertEquals(1024, store.size())
    }
}
