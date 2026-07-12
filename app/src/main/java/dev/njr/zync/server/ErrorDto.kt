package dev.njr.zync.server

import kotlinx.serialization.Serializable

/** Generic JSON error body returned by the loopback/LAN server (StatusPages + auth failures). */
@Serializable
data class ErrorDto(val error: String)
