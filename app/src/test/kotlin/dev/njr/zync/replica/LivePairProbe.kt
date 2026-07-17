package dev.njr.zync.replica

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** TEMPORARY live probe: drive the real phone pairing stack at dev.choosh.ai. */
@RunWith(RobolectricTestRunner::class)
class LivePairProbe {
    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun probe() = runBlocking {
        val k = java.net.URLEncoder.encode(Base64.encode(ByteArray(32)), Charsets.UTF_8)
        val h = java.net.URLEncoder.encode("https://dev.choosh.ai", Charsets.UTF_8)
        val uri = "zync://pair?h=$h&k=$k&c=BOGUS123&e=9999999999999"
        val http = io.ktor.client.HttpClient(io.ktor.client.engine.cio.CIO)
        val store = object : PairingStore {
            override fun save(server: PairedServer) {}
            override fun load(): PairedServer? = null
        }
        try {
            val outcome = pairFromUri(http, uri, replicaId = "probe-replica", store = store)
            println("PROBE OUTCOME: $outcome")
        } finally { http.close() }
    }
}
