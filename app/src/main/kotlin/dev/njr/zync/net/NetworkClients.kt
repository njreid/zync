package dev.njr.zync.net

import dev.njr.zync.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header

/**
 * The one place both of [dev.njr.zync.ZyncApp]'s HTTP call sites (device pairing and
 * op-log sync) build their client, so the Cloudflare Access service-token headers
 * (Task 5/6 of the shared-host migration) land on every non-interactive request
 * without each call site remembering to add them.
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
