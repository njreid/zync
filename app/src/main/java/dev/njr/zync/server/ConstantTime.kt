package dev.njr.zync.server

import java.security.MessageDigest

/**
 * Constant-time equality of two strings, compared as UTF-8 bytes. Used by the loopback token
 * guard so a timing side-channel can't be used to recover the per-boot token byte by byte.
 */
internal fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
