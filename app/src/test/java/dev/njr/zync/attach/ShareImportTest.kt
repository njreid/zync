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
        assertNull(ShareImport.typeFor("image/jpeg"))
        assertNull(ShareImport.typeFor("text/plain"))
        assertNull(ShareImport.typeFor(null))
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
