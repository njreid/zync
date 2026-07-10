package dev.njr.zync.replica

import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.clock.HlcGenerator

/** Persists the phone's last HLC so offline monotonicity survives process death. */
interface HlcStore {
    fun load(): Hlc?
    fun save(hlc: Hlc)
}

/**
 * The phone's shared hybrid logical clock. Both the op writer (`now()`) and the sync
 * client (`observe()` on pulled ops) advance it, and every advance is persisted via
 * [HlcStore] — so a task created offline after a restart still sorts after everything
 * the phone had already seen.
 */
class LocalHlc(
    private val store: HlcStore,
    deviceId: String,
    clock: Clock,
) {
    private val generator = HlcGenerator(deviceId, clock, store.load() ?: Hlc.zero(deviceId))

    @Synchronized
    fun now(): Hlc = generator.now().also(store::save)

    @Synchronized
    fun observe(remote: Hlc): Hlc = generator.observe(remote).also(store::save)

    fun current(): Hlc = generator.current()
}
