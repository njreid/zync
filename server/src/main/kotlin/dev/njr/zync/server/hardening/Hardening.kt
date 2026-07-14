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

/** The abuse surfaces the hardening interceptor covers (sync, blobs, pairing, browser auth). */
private val PROTECTED_PREFIXES = listOf("/sync", "/blob", "/pair", "/auth", "/login")

/**
 * Intercepts protected paths ([PROTECTED_PREFIXES]): rejects oversized requests (413),
 * rate-limits by client IP (429), logs one structured line per request, and counts
 * requests/rejections. `/health` and `/metrics` are exempt. Auth failures (401/403)
 * on protected paths are counted observation-only on the way out.
 *
 * The limiter key is the normalized remote address, never a client-supplied header:
 * this runs before authentication, and keying on `X-Device-Id` would let an attacker
 * both evade per-IP limiting and grow the bucket map by rotating header values.
 */
fun Application.installHardening(hardening: Hardening) {
    val log = LoggerFactory.getLogger("zync.access")

    intercept(ApplicationCallPipeline.Plugins) {
        val path = call.request.path()
        if (PROTECTED_PREFIXES.none { path.startsWith(it) }) return@intercept

        hardening.metrics.onRequest()
        val principal = call.request.origin.remoteHost

        val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (contentLength != null && contentLength > hardening.maxRequestBytes) {
            hardening.metrics.onOversized()
            log.warn("reject oversized method={} path={} principal={} bytes={}", call.request.local.method.value, path, principal, contentLength)
            call.respondText("payload too large", status = HttpStatusCode.PayloadTooLarge)
            return@intercept finish()
        }

        if (!hardening.rateLimiter.tryAcquire(principal)) {
            hardening.metrics.onRateLimited()
            log.warn("rate limit method={} path={} principal={}", call.request.local.method.value, path, principal)
            call.respondText("rate limited", status = HttpStatusCode.TooManyRequests)
            return@intercept finish()
        }

        log.info("request method={} path={} principal={}", call.request.local.method.value, path, principal)

        // Observation-only: count auth refusals (threat model T1) without changing behavior.
        proceed()
        val status = call.response.status()
        if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
            hardening.metrics.onAuthFailure()
        }
    }
}
