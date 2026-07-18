package dev.njr.zync.server

import dev.njr.zync.core.agenda.AgendaEventDto
import dev.njr.zync.core.agenda.AgendaPush
import dev.njr.zync.core.agenda.AgendaSnapshot
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.agenda.AgendaEndpoint
import dev.njr.zync.server.sync.SyncService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The agenda side channel: token-gated replace-by-source push, merged read. */
class AgendaRoutesTest {
    private val json = Json

    private fun push(vararg events: AgendaEventDto) = json.encodeToString(AgendaPush.serializer(), AgendaPush(events.toList()))
    private fun event(title: String, begin: Long, end: Long, profile: String = "WORK") =
        AgendaEventDto(title, begin, end, profile = profile)

    @Test
    fun pushReplacesSourceWholesaleAndGetMerges() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        application { zyncModule(SyncService(db), agenda = AgendaEndpoint(db, ingestToken = "sekrit")) }
        val far = System.currentTimeMillis() + 60 * 60_000

        suspend fun postAgenda(source: String, body: String) = client.post("/agenda/$source") {
            header(HttpHeaders.Authorization, "Bearer sekrit")
            header(HttpHeaders.ContentType, "application/json")
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, postAgenda("work", push(event("Standup", far, far + 1))).status)
        assertEquals(HttpStatusCode.OK, postAgenda("home", push(event("Dentist", far, far + 1, profile = "HOME"))).status)
        // replace: the second work push wins wholesale
        assertEquals(HttpStatusCode.OK, postAgenda("work", push(event("Review", far, far + 1))).status)

        val snapshot = json.decodeFromString(AgendaSnapshot.serializer(), client.get("/agenda").bodyAsText())
        assertEquals(setOf("Review", "Dentist"), snapshot.events.map { it.title }.toSet())
    }

    @Test
    fun pushDemandsTheIngestToken() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        application { zyncModule(SyncService(db), agenda = AgendaEndpoint(db, ingestToken = "sekrit")) }
        val far = System.currentTimeMillis() + 60 * 60_000

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/agenda/work") {
                header(HttpHeaders.ContentType, "application/json")
                setBody(push(event("Standup", far, far + 1)))
            }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/agenda/work") {
                header(HttpHeaders.Authorization, "Bearer wrong")
                header(HttpHeaders.ContentType, "application/json")
                setBody(push(event("Standup", far, far + 1)))
            }.status,
        )
    }

    @Test
    fun unconfiguredTokenDisablesIngestionEntirely() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        application { zyncModule(SyncService(db), agenda = AgendaEndpoint(db, ingestToken = null)) }
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/agenda/work") {
                header(HttpHeaders.Authorization, "Bearer anything")
                header(HttpHeaders.ContentType, "application/json")
                setBody(push(event("Standup", System.currentTimeMillis() + 60_000, System.currentTimeMillis() + 120_000)))
            }.status,
        )
    }

    @Test
    fun invalidEventsAreRejected() = testApplication {
        val db = JvmZyncDatabase.inMemory()
        application { zyncModule(SyncService(db), agenda = AgendaEndpoint(db, ingestToken = "sekrit")) }
        val far = System.currentTimeMillis() + 60 * 60_000
        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/agenda/work") {
                header(HttpHeaders.Authorization, "Bearer sekrit")
                header(HttpHeaders.ContentType, "application/json")
                setBody(push(event("Backwards", far, far - 1)))
            }.status,
        )
    }
}
