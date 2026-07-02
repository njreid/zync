package dev.njr.zync.server

import dev.njr.zync.data.NodeKind
import dev.njr.zync.data.NodeStatus
import dev.njr.zync.data.ZyncDatabase
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NodesApiTest {

    @Test
    fun `roots lists seeded builtin folders`() = zyncTestApplication { _, _, client ->
        val roots: List<NodeDto> = client.get("/api/roots").body()
        assertEquals(listOf("Inbox", "Someday"), roots.map { it.title })
        assertTrue(roots.all { it.builtin && it.kind == NodeKind.FOLDER })
    }

    @Test
    fun `quick add creates task in inbox`() = zyncTestApplication { _, _, client ->
        val res = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("buy milk"))
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val dto: NodeDto = res.body()
        assertEquals(ZyncDatabase.INBOX_ID, dto.parentId)
        val children: List<NodeDto> = client.get("/api/nodes/${ZyncDatabase.INBOX_ID}/children").body()
        assertEquals(listOf("buy milk"), children.map { it.title })
    }

    @Test
    fun `create move convert complete trash roundtrip`() = zyncTestApplication { _, _, client ->
        suspend fun create(kind: NodeKind, parentId: Long?, title: String): NodeDto =
            client.post("/api/nodes") {
                contentType(ContentType.Application.Json)
                setBody(CreateNodeBody(kind, parentId, title))
            }.body()

        val folder = create(NodeKind.FOLDER, null, "Work")
        val project = create(NodeKind.PROJECT, folder.id, "Site")
        val task: NodeDto = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("write copy"))
        }.body()

        val moved: NodeDto = client.post("/api/nodes/${task.id}/move") {
            contentType(ContentType.Application.Json); setBody(MoveBody(project.id))
        }.body()
        assertEquals(project.id, moved.parentId)

        val second: NodeDto = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("plan party"))
        }.body()
        val converted: NodeDto = client.post("/api/nodes/${second.id}/convert") {
            contentType(ContentType.Application.Json); setBody(ConvertBody(folder.id))
        }.body()
        assertEquals(NodeKind.PROJECT, converted.kind)
        assertEquals(folder.id, converted.parentId)

        val done: NodeDto = client.post("/api/nodes/${task.id}/complete").body()
        assertEquals(NodeStatus.DONE, done.status)
        val reopened: NodeDto = client.post("/api/nodes/${task.id}/reopen").body()
        assertEquals(NodeStatus.ACTIVE, reopened.status)
        assertNull(reopened.completedAt)
        val trashed: NodeDto = client.post("/api/nodes/${task.id}/trash").body()
        assertEquals(NodeStatus.DROPPED, trashed.status)
    }

    @Test
    fun `patch edits title and notes`() = zyncTestApplication { _, _, client ->
        val task: NodeDto = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("draft"))
        }.body()
        val patched: NodeDto = client.patch("/api/nodes/${task.id}") {
            contentType(ContentType.Application.Json)
            setBody(PatchNodeBody(title = "final", notes = "details"))
        }.body()
        assertEquals("final", patched.title)
        assertEquals("details", patched.notes)
    }

    @Test
    fun `defer sets and clears`() = zyncTestApplication { _, _, client ->
        val task: NodeDto = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("later"))
        }.body()
        val deferred: NodeDto = client.post("/api/nodes/${task.id}/defer") {
            contentType(ContentType.Application.Json); setBody(DeferBody(99999L))
        }.body()
        assertEquals(99999L, deferred.deferUntil)
        val cleared: NodeDto = client.post("/api/nodes/${task.id}/defer") {
            contentType(ContentType.Application.Json); setBody(DeferBody(null))
        }.body()
        assertNull(cleared.deferUntil)
    }

    @Test
    fun `rule violations map to 400 with message`() = zyncTestApplication { _, _, client ->
        val res = client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody(CreateNodeBody(NodeKind.TASK, null, "root task"))
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        val err: ErrorDto = res.body()
        assertTrue(err.error.isNotBlank())
        assertEquals(HttpStatusCode.BadRequest,
            client.post("/api/nodes/${ZyncDatabase.INBOX_ID}/trash").status)
    }

    @Test
    fun `get unknown node is 404`() = zyncTestApplication { _, _, client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/api/nodes/9999").status)
    }

    @Test
    fun `patch unknown node with empty body is 404 not 500`() = zyncTestApplication { _, _, client ->
        val res = client.patch("/api/nodes/9999") {
            contentType(ContentType.Application.Json); setBody(PatchNodeBody())
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun `non-numeric id is 400`() = zyncTestApplication { _, _, client ->
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/nodes/abc").status)
    }

    @Test
    fun `children of unknown node is 404 not empty list`() = zyncTestApplication { _, _, client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/api/nodes/9999/children").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/nodes/9999/contexts").status)
    }
}
