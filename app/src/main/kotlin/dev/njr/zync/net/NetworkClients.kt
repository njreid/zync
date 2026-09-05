package dev.njr.zync.net

import dev.njr.zync.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header

/**
 * The one place every non-interactive HTTP call site against the zync server
 * ([dev.njr.zync.ZyncApp]'s device pairing and op-log sync, and
 * [dev.njr.zync.MainActivity]'s newz handoff mint) builds its client, so the
 * Cloudflare Access service-token headers (Task 5/6 of the shared-host migration)
 * land on every such request without each call site remembering to add them.
 *
 * [credentials] and [engine] default to the real BuildConfig-derived credentials and
 * OkHttp; tests override them (e.g. with a `MockEngine`) to assert on the headers a
 * built request actually carries.
 */
fun buildZyncHttpClient(
    credentials: CfAccessCredentials? =
        CfAccessCredentials.from(BuildConfig.CF_ACCESS_CLIENT_ID, BuildConfig.CF_ACCESS_CLIENT_SECRET),
    engine: HttpClientEngine = OkHttp.create(),
): HttpClient = HttpClient(engine) {
    if (credentials != null) {
        defaultRequest {
            credentials.headers.forEach { (name, value) -> header(name, value) }
        }
    }
}
