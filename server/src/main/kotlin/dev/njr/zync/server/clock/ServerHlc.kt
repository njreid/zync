package dev.njr.zync.server.clock

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.clock.HlcGenerator

/** Persists the server's last-issued HLC (mirrors the phone's [dev.njr.zync.replica.HlcStore]). */
interface HlcStore {
    fun load(): Hlc?
    fun save(hlc: Hlc)
}

/**
 * The server's ONE shared hybrid logical clock — both browser-authored ops
 * ([dev.njr.zync.server.content.ServerOpEmitter]) and bot-authored ops
 * ([dev.njr.zync.server.api.RecordingBotEmitter]) advance it, so they stay mutually ordered.
 * [HlcGenerator] is documented not-thread-safe ("callers serialize op issuance"); the server
 * handles concurrent requests, so every access here is `@Synchronized`, matching the phone's
 * [dev.njr.zync.replica.LocalHlc] pattern. Every advance is persisted via [store].
 */
class ServerHlc(
    private val store: HlcStore,
    deviceId: String = "server",
    clock: Clock = Clock { System.currentTimeMillis() },
) {
    private val generator = HlcGenerator(deviceId, clock, store.load() ?: Hlc.zero(deviceId))

    @Synchronized
    fun now(): Hlc = generator.now().also(store::save)

    @Synchronized
    fun observe(remote: Hlc): Hlc = generator.observe(remote).also(store::save)

    fun current(): Hlc = generator.current()
}
