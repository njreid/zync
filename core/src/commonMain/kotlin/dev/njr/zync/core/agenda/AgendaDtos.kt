package dev.njr.zync.core.agenda

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the agenda side channel: externally-owned calendar events pushed
 * by an ingestion adapter (Apps Script, extension, …) and pulled by replicas at
 * sync time. Deliberately NOT op-log data — ephemeral, high-churn, replaced
 * wholesale per source on every push.
 */
@Serializable
data class AgendaEventDto(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    /** "WORK" or "HOME" — drives the agenda identity color on the phone. */
    val profile: String = "WORK",
    /** Free-text location; a URL inside becomes a "join" link on the phone. */
    val location: String? = null,
)

/** `POST /agenda/{source}` body: this source's upcoming events, replacing the last push. */
@Serializable
data class AgendaPush(val events: List<AgendaEventDto>)

/** `GET /agenda` response: all sources' upcoming events, merged. */
@Serializable
data class AgendaSnapshot(val events: List<AgendaEventDto>)
