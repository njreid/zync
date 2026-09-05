package dev.njr.zync.net

import dev.njr.zync.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header

/**
 * The one place every non-interactive HTTP call site against the zync server
 * ([dev.njr.zync.ZyncApp]'s device pairing and op-log sync, and
 * [dev.njr.zync.MainActivity]'s newz handoff mint) builds its client, so the
 * Cloudflare Access service-token headers (Task 5/6 of the shared-host migration)
 * land on every such request without each call site remembering to add them.
 */
fun buildZyncHttpClient(): HttpClient {
    val credentials = CfAccessCredentials.from(BuildConfig.CF_ACCESS_CLIENT_ID, BuildConfig.CF_ACCESS_CLIENT_SECRET)
    return HttpClient(OkHttp) {
        if (credentials != null) {
            defaultRequest {
                credentials.headers.forEach { (name, value) -> header(name, value) }
            }
        }
    }
}
