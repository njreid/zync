package dev.njr.zync.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.attach.AttachmentStore
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

fun zyncTestApplication(
    attachmentStore: AttachmentStore? = null,
    block: suspend ApplicationTestBuilder.(ZyncDatabase, NodeRepository, HttpClient) -> Unit,
) {
    val db = ZyncDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
    val repo = NodeRepository(db)
    try {
        testApplication {
            application {
                zyncModule(db, repo, token = "test-token", assets = { path ->
                    if (path == "index.html")
                        "<html>ok</html>".toByteArray() to ContentType.Text.Html
                    else null
                }, attachmentStore = attachmentStore)
            }
            val client = createClient {
                install(ContentNegotiation) { json() }
                defaultRequest { headers.append(TOKEN_HEADER, "test-token") }
            }
            block(db, repo, client)
        }
    } finally {
        db.close()
    }
}
