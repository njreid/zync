package dev.njr.zync.server.clock

import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.data.db.ZyncDatabase

/** SQLDelight-backed [HlcStore] for the server. */
class SqlHlcStore(private val db: ZyncDatabase) : HlcStore {
    override fun load(): Hlc? =
        db.serverHlcQueries.loadServerHlc().executeAsOneOrNull()?.let { Hlc(it.physical, it.counter.toInt(), it.device_id) }

    override fun save(hlc: Hlc) {
        db.serverHlcQueries.saveServerHlc(hlc.physical, hlc.counter.toLong(), hlc.deviceId)
    }
}
