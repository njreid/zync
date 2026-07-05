package dev.njr.zync.server

import dev.njr.zync.data.AttachmentEntity
import dev.njr.zync.data.AttachmentType
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
}
