package dev.njr.zync.attach

import dev.njr.zync.data.AttachmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareImportTest {

    @Test
    fun `audio mimes map to AUDIO`() {
        assertEquals(AttachmentType.AUDIO, ShareImport.typeFor("audio/mpeg"))
        assertEquals(AttachmentType.AUDIO, ShareImport.typeFor("audio/mp4"))
        assertEquals(AttachmentType.AUDIO, ShareImport.typeFor("audio/ogg"))
    }

    @Test
    fun `pdf maps to PDF`() {
        assertEquals(AttachmentType.PDF, ShareImport.typeFor("application/pdf"))
    }

    @Test
    fun `unsupported and null mimes are rejected`() {
        // text/plain is a NOTE capture upstream, never an attachment
        assertNull(ShareImport.typeFor("text/plain"))
        assertNull(ShareImport.typeFor("video/mp4"))
        assertNull(ShareImport.typeFor(null))
    }

    @Test
    fun `images import as attachments with their own title`() {
        assertEquals(AttachmentType.PDF, ShareImport.typeFor("image/jpeg"))
        assertEquals("jpg", ShareImport.extensionFor("image/jpeg"))
        assertEquals("png", ShareImport.extensionFor("image/png"))
        assertEquals("Shared image", ShareImport.titleFor(AttachmentType.PDF, "image/png"))
    }

    @Test
    fun `shared text titles come from subject then compact url then text`() {
        assertEquals("Great article", ShareImport.titleForText("Great article", "https://x.com/a?b=c"))
        assertEquals("x.com/a", ShareImport.titleForText(null, "look https://www.x.com/a?utm=1"))
        assertEquals("just a thought", ShareImport.titleForText(null, "just a thought"))
        assertEquals("https://www.x.com/a?utm=1", ShareImport.firstUrl("look https://www.x.com/a?utm=1"))
    }

    @Test
    fun `extension is derived from mime subtype`() {
        assertEquals("pdf", ShareImport.extensionFor("application/pdf"))
        assertEquals("mp3", ShareImport.extensionFor("audio/mpeg"))
        assertEquals("m4a", ShareImport.extensionFor("audio/mp4"))
        assertEquals("m4a", ShareImport.extensionFor("audio/x-m4a"))
        assertEquals("ogg", ShareImport.extensionFor("audio/ogg"))
        assertEquals("wav", ShareImport.extensionFor("audio/wav"))
    }

    @Test
    fun `titles are human readable per type`() {
        assertEquals("Voice note", ShareImport.titleFor(AttachmentType.AUDIO))
        assertEquals("Scanned document", ShareImport.titleFor(AttachmentType.PDF))
    }
}
