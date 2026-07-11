package dev.njr.zync.server

import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.content.ServerContent
import dev.njr.zync.server.sync.SyncService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/**
 * Dev-only server that serves the shared `:web` UI over an in-memory store with seeded
 * content and no auth — for driving the browser UX headlessly (Playwright). NOT for prod.
 * Run: `./gradlew :server:webDevServer` (binds ZYNC_DEV_PORT or 8099).
 */
fun main() {
    val port = System.getenv("ZYNC_DEV_PORT")?.toInt() ?: 8099
    val service = SyncService(JvmZyncDatabase.inMemory())
    val content = ServerContent(service)
    content.commands.createTask("Buy milk")
    content.commands.createTask("Read a book")

    embeddedServer(Netty, port = port) {
        zyncModule(service, content = content)
    }.start(wait = true)
}
