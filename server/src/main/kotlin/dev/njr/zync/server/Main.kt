package dev.njr.zync.server

import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.sync.SyncService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/** Entry point: open the durable DB, wire the sync service, serve. */
fun main() {
    val dbPath = System.getenv("ZYNC_DB_PATH") ?: "zync.db"
    val port = System.getenv("ZYNC_PORT")?.toInt() ?: 8080
    val service = SyncService(JvmZyncDatabase.file(dbPath))
    embeddedServer(Netty, port = port) {
        zyncModule(service)
    }.start(wait = true)
}
