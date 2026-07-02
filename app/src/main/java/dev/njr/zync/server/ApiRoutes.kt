package dev.njr.zync.server

import dev.njr.zync.data.NodeKind
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class TitleBody(val title: String)
@Serializable data class CreateNodeBody(val kind: NodeKind, val parentId: Long?, val title: String)
@Serializable data class PatchNodeBody(val title: String? = null, val notes: String? = null)
@Serializable data class DeferBody(val until: Long?)
@Serializable data class MoveBody(val parentId: Long)
@Serializable data class ConvertBody(val folderId: Long)
@Serializable data class NameBody(val name: String)
@Serializable data class ContextIdsBody(val contextIds: List<Long>)
@Serializable data class EventDto(val type: String)

fun Route.apiRoutes(db: ZyncDatabase, repo: NodeRepository) {
    route("/api") {
        get("/roots") { call.respond(repo.observeRoots().first().map { it.toDto() }) }

        post("/inbox") {
            val id = repo.quickAddTask(call.receive<TitleBody>().title)
            call.respond(HttpStatusCode.Created, repo.get(id)!!.toDto())
        }

        post("/nodes") {
            val b = call.receive<CreateNodeBody>()
            val id = repo.createNode(b.kind, b.parentId, b.title)
            call.respond(HttpStatusCode.Created, repo.get(id)!!.toDto())
        }

        get("/contexts") { call.respond(repo.observeContexts().first().map { it.toDto() }) }
        post("/contexts") {
            val id = repo.createContext(call.receive<NameBody>().name)
            val ctx = repo.observeContexts().first().first { it.id == id }
            call.respond(HttpStatusCode.Created, ctx.toDto())
        }
        get("/contexts/{id}/tasks") {
            call.respond(repo.observeTasksInContext(id()).first().map { it.toDto() })
        }

        get("/destinations") {
            call.respond(repo.observeDestinations().first().map { it.toDto() })
        }

        webSocket("/events") {
            send(Frame.Text(Json.encodeToString(EventDto("hello"))))
            db.changesFlow().collect {
                send(Frame.Text(Json.encodeToString(EventDto("changed"))))
            }
        }

        route("/nodes/{id}") {
            get {
                val node = repo.get(id()) ?: return@get call.respond(
                    HttpStatusCode.NotFound, ErrorDto("no such node"))
                call.respond(node.toDto())
            }
            get("/children") {
                call.respond(repo.observeChildren(id()).first().map { it.toDto() })
            }
            patch {
                val node = repo.get(id()) ?: return@patch call.respond(
                    HttpStatusCode.NotFound, ErrorDto("no such node"))
                val b = call.receive<PatchNodeBody>()
                b.title?.let { repo.rename(id(), it) }
                b.notes?.let { repo.setNotes(id(), it) }
                call.respond(repo.get(id())!!.toDto())
            }
            post("/defer") {
                repo.setDefer(id(), call.receive<DeferBody>().until)
                call.respond(repo.get(id())!!.toDto())
            }
            post("/move") {
                repo.move(id(), call.receive<MoveBody>().parentId)
                call.respond(repo.get(id())!!.toDto())
            }
            post("/convert") {
                repo.convertTaskToProject(id(), call.receive<ConvertBody>().folderId)
                call.respond(repo.get(id())!!.toDto())
            }
            post("/complete") { repo.complete(id()); call.respond(repo.get(id())!!.toDto()) }
            post("/reopen") { repo.reopen(id()); call.respond(repo.get(id())!!.toDto()) }
            post("/trash") { repo.trash(id()); call.respond(repo.get(id())!!.toDto()) }
            get("/contexts") {
                call.respond(repo.observeContextsFor(id()).first().map { it.toDto() })
            }
            put("/contexts") {
                repo.setContexts(id(), call.receive<ContextIdsBody>().contextIds.toSet())
                call.respond(repo.observeContextsFor(id()).first().map { it.toDto() })
            }
        }
    }
}

private fun io.ktor.server.routing.RoutingContext.id(): Long =
    requireNotNull(call.parameters["id"]?.toLongOrNull()) { "invalid id" }
