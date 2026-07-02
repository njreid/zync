package dev.njr.zync

import android.app.Application
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import dev.njr.zync.server.ZyncServer
import dev.njr.zync.server.androidAssets
import java.util.UUID

class ZyncApp : Application() {
    val database: ZyncDatabase by lazy { ZyncDatabase.build(this) }
    val repository: NodeRepository by lazy { NodeRepository(database) }
    val serverToken: String = UUID.randomUUID().toString()

    private var server: ZyncServer? = null
    private var boundPort: Int = -1

    /** Blocks while binding — call from a background thread. */
    @Synchronized
    fun ensureServerStarted(): Int {
        if (server == null) {
            server = ZyncServer(database, repository, serverToken, androidAssets(assets))
            boundPort = server!!.start()
        }
        return boundPort
    }
}
