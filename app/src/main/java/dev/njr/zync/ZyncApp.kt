package dev.njr.zync

import android.app.Application
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository

class ZyncApp : Application() {
    val database: ZyncDatabase by lazy { ZyncDatabase.build(this) }
    val repository: NodeRepository by lazy { NodeRepository(database) }
}
