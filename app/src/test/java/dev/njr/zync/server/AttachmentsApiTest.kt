package dev.njr.zync.server

import dev.njr.zync.attach.AttachmentStore
import dev.njr.zync.data.AttachmentEntity
import dev.njr.zync.data.AttachmentType
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentsApiTest {

    @Test
    fun `attachments route returns linked attachments for a node`() = zyncTestApplication { db, _, client ->
        val task: NodeDto = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("scan receipt"))
        }.body()
        db.nodeDao().insertAttachment(
            AttachmentEntity(nodeId = task.id, type = AttachmentType.PDF, relativePath = "pdf/ab/deadbeef.pdf")
        )
        db.nodeDao().insertAttachment(
            AttachmentEntity(nodeId = task.id, type = AttachmentType.OCR_TEXT, relativePath = "ocr_text/ab/deadbeef.txt")
        )

        val attachments: List<AttachmentDto> = client.get("/api/nodes/${task.id}/attachments").body()
        assertEquals(2, attachments.size)
        assertTrue(attachments.all { it.nodeId == task.id })
        assertEquals(
            setOf(AttachmentType.PDF, AttachmentType.OCR_TEXT),
            attachments.map { it.type }.toSet(),
        )
    }

    @Test
    fun `attachments route is empty list for a node with none`() = zyncTestApplication { _, _, client ->
        val task: NodeDto = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("bare"))
        }.body()
        val attachments: List<AttachmentDto> = client.get("/api/nodes/${task.id}/attachments").body()
        assertTrue(attachments.isEmpty())
    }

    @Test
    fun `attachments route for unknown node is 404`() = zyncTestApplication { _, _, client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/api/nodes/9999/attachments").status)
    }

    @Test
    fun `attachments route non-numeric id is 400`() = zyncTestApplication { _, _, client ->
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/nodes/abc/attachments").status)
    }

    @Test
    fun `download returns bytes with content-type and nosniff`() {
        val store = AttachmentStore(Files.createTempDirectory("zync-attach").toFile())
        val payload = "%PDF-1.4 pretend document".toByteArray()
        val relativePath = store.write(payload, AttachmentType.PDF, "pdf")
        zyncTestApplication(store) { db, _, client ->
            val task: NodeDto = client.post("/api/inbox") {
                contentType(ContentType.Application.Json); setBody(TitleBody("scan"))
            }.body()
            val attachmentId = db.attachmentDao().insert(
                AttachmentEntity(nodeId = task.id, type = AttachmentType.PDF, relativePath = relativePath)
            )
            val resp = client.get("/api/nodes/${task.id}/attachments/$attachmentId")
            assertEquals(HttpStatusCode.OK, resp.status)
            assertArrayEquals(payload, resp.body<ByteArray>())
            assertEquals(ContentType.Application.Pdf, resp.contentType())
            assertEquals("nosniff", resp.headers["X-Content-Type-Options"])
        }
    }

    @Test
    fun `download for an attachment owned by another node is 404`() {
        val store = AttachmentStore(Files.createTempDirectory("zync-attach").toFile())
        val relativePath = store.write("data".toByteArray(), AttachmentType.PDF, "pdf")
        zyncTestApplication(store) { db, _, client ->
            val owner: NodeDto = client.post("/api/inbox") {
                contentType(ContentType.Application.Json); setBody(TitleBody("owner"))
            }.body()
            val other: NodeDto = client.post("/api/inbox") {
                contentType(ContentType.Application.Json); setBody(TitleBody("other"))
            }.body()
            val attachmentId = db.attachmentDao().insert(
                AttachmentEntity(nodeId = owner.id, type = AttachmentType.PDF, relativePath = relativePath)
            )
            // Requesting the attachment through a node that does not own it must
            // not leak it (IDOR guard).
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/nodes/${other.id}/attachments/$attachmentId").status,
            )
        }
    }

    @Test
    fun `download is 404 when the backing file is missing`() {
        val store = AttachmentStore(Files.createTempDirectory("zync-attach").toFile())
        zyncTestApplication(store) { db, _, client ->
            val task: NodeDto = client.post("/api/inbox") {
                contentType(ContentType.Application.Json); setBody(TitleBody("orphan row"))
            }.body()
            val attachmentId = db.attachmentDao().insert(
                AttachmentEntity(nodeId = task.id, type = AttachmentType.PDF, relativePath = "pdf/ab/missing.pdf")
            )
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/nodes/${task.id}/attachments/$attachmentId").status,
            )
        }
    }

    @Test
    fun `download for unknown attachment id is 404`() {
        val store = AttachmentStore(Files.createTempDirectory("zync-attach").toFile())
        zyncTestApplication(store) { _, _, client ->
            val task: NodeDto = client.post("/api/inbox") {
                contentType(ContentType.Application.Json); setBody(TitleBody("bare"))
            }.body()
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/api/nodes/${task.id}/attachments/9999").status,
            )
        }
    }

    @Test
    fun `download with non-numeric attachment id is 400`() {
        val store = AttachmentStore(Files.createTempDirectory("zync-attach").toFile())
        zyncTestApplication(store) { _, _, client ->
            val task: NodeDto = client.post("/api/inbox") {
                contentType(ContentType.Application.Json); setBody(TitleBody("bare"))
            }.body()
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/nodes/${task.id}/attachments/abc").status,
            )
        }
    }
}
