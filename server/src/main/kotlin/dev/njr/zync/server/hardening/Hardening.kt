package dev.njr.zync.server.hardening

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import org.slf4j.LoggerFactory

/**
 * Baseline hardening + observability (the threat-model baseline; full items tracked
 * there). Bundles a per-principal [rateLimiter], a request-size cap, and [metrics].
 */
class Hardening(
    val rateLimiter: RateLimiter,
    val maxRequestBytes: Long = 32L * 1024 * 1024,
    val metrics: Metrics = Metrics(),
)

/**
 * Intercepts protected paths (`/sync`, `/blob`): rejects oversized requests (413),
 * rate-limits by device id or client IP (429), logs one structured line per request,
 * and counts requests/rejections. `/health` and `/metrics` are exempt.
 */
fun Application.installHardening(hardening: Hardening) {
    val log = LoggerFactory.getLogger("zync.access")

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (!(path.startsWith("/sync") || path.startsWith("/blob"))) return@intercept

        hardening.metrics.onRequest()
        val principal = call.request.header("X-Device-Id") ?: call.request.origin.remoteHost

        val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (contentLength != null && contentLength > hardening.maxRequestBytes) {
            hardening.metrics.onRejected()
            log.warn("reject oversized method={} path={} principal={} bytes={}", call.request.local.method.value, path, principal, contentLength)
            call.respondText("payload too large", status = HttpStatusCode.PayloadTooLarge)
            return@intercept finish()
        }

        if (!hardening.rateLimiter.tryAcquire(principal)) {
            hardening.metrics.onRejected()
            log.warn("rate limit method={} path={} principal={}", call.request.local.method.value, path, principal)
            call.respondText("rate limited", status = HttpStatusCode.TooManyRequests)
            return@intercept finish()
        }

        log.info("request method={} path={} principal={}", call.request.local.method.value, path, principal)
    }
}
