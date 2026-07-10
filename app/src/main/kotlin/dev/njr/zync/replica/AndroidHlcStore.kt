package dev.njr.zync.replica

import android.content.Context
import dev.njr.zync.core.clock.Hlc

/** SharedPreferences-backed [HlcStore] for the phone. */
class AndroidHlcStore(context: Context) : HlcStore {
    private val prefs = context.getSharedPreferences("zync_hlc", Context.MODE_PRIVATE)

    override fun load(): Hlc? = prefs.getString(KEY, null)?.let(Hlc::unpack)

    override fun save(hlc: Hlc) {
        prefs.edit().putString(KEY, hlc.pack()).apply()
    }

    private companion object {
        const val KEY = "last_hlc"
    }
}
