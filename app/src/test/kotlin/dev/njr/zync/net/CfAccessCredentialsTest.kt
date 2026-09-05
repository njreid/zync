package dev.njr.zync.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CfAccessCredentialsTest {
    @Test
    fun `blank client id yields no credentials`() {
        val creds = CfAccessCredentials.from(clientId = "", clientSecret = "secret")
        assertNull(creds)
    }

    @Test
    fun `blank client secret yields no credentials`() {
        val creds = CfAccessCredentials.from(clientId = "id", clientSecret = "")
        assertNull(creds)
    }

    @Test
    fun `both blank yields no credentials`() {
        val creds = CfAccessCredentials.from(clientId = "", clientSecret = "")
        assertNull(creds)
    }

    @Test
    fun `whitespace-only client id yields no credentials`() {
        val creds = CfAccessCredentials.from(clientId = " ", clientSecret = "secret")
        assertNull(creds)
    }

    @Test
    fun `both present yields the header pair`() {
        val creds = requireNotNull(CfAccessCredentials.from(clientId = "id123", clientSecret = "secretXYZ"))
        assertEquals(mapOf("CF-Access-Client-Id" to "id123", "CF-Access-Client-Secret" to "secretXYZ"), creds.headers)
    }
}
