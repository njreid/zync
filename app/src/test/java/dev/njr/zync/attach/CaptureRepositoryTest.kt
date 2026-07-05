package dev.njr.zync.attach

import android.net.Uri
import dev.njr.zync.data.AttachmentType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureRepositoryTest {
    @Test
    fun `attachment type follows mime type`() {
        assertEquals(AttachmentType.AUDIO, CaptureRepository.attachmentTypeFor("audio/mp4", Uri.parse("content://x/a")))
        assertEquals(AttachmentType.PDF, CaptureRepository.attachmentTypeFor("application/pdf", Uri.parse("content://x/a")))
        assertEquals(AttachmentType.TRANSCRIPT, CaptureRepository.attachmentTypeFor("text/plain", Uri.parse("content://x/a")))
        assertEquals(AttachmentType.PDF, CaptureRepository.attachmentTypeFor(null, Uri.parse("content://x/a.pdf")))
    }

    @Test
    fun `extension follows mime type`() {
        assertEquals("m4a", CaptureRepository.extensionFor("audio/mp4", Uri.parse("content://x/a")))
        assertEquals("pdf", CaptureRepository.extensionFor("application/pdf", Uri.parse("content://x/a")))
        assertEquals("jpg", CaptureRepository.extensionFor("image/png", Uri.parse("content://x/a")))
        assertEquals("txt", CaptureRepository.extensionFor("text/plain", Uri.parse("content://x/a")))
        assertEquals("bin", CaptureRepository.extensionFor(null, Uri.parse("content://x/a")))
    }

    @Test
    fun `timestamp titles are stable format`() {
        assertEquals(21, CaptureRepository.timestampTitle("Scan", 0L).length)
        assertEquals("Scan ", CaptureRepository.timestampTitle("Scan", 0L).take(5))
    }
}
