package dev.njr.zync.server

import java.security.MessageDigest

/** Lowercase hex SHA-256 of [bytes]. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
