package dev.njr.zync.server

import dev.njr.zync.core.agent.AgentFlow
import dev.njr.zync.core.clock.Clock
import dev.njr.zync.core.clock.Hlc
import dev.njr.zync.core.id.Ulid
import dev.njr.zync.core.op.Actor
import dev.njr.zync.core.op.EntityType
import dev.njr.zync.core.op.Op
import dev.njr.zync.data.JvmZyncDatabase
import dev.njr.zync.server.content.ServerContent
import dev.njr.zync.server.sync.SyncService
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * Dev-only server that serves the shared `:web` UI over an in-memory store with seeded
 * content and no auth — for driving the browser UX headlessly (Playwright). NOT for prod.
 * Run: `./gradlew :server:webDevServer` (binds ZYNC_DEV_PORT or 8099).
 */
fun main() {
    val port = System.getenv("ZYNC_DEV_PORT")?.toInt() ?: 8099
    val service = SyncService(JvmZyncDatabase.inMemory())
    val content = ServerContent(service)
    content.commands.createTask("Kbd complete me") // gestures.spec keyboard test completes the first row
    content.commands.createTask("Buy milk")
    content.commands.createTask("Read a book")
    content.commands.createTask("CSP probe task") // csp.spec completes this one
    content.commands.createTask("Swipe me done") // gestures.spec swipes these
    content.commands.createTask("Swipe me gone")
    val offsite = content.commands.createTask("Plan the offsite")
    seedStubProposal(service, offsite)
    // A context with one tagged task, so the context-pill filter is drivable.
    content.commands.addTag(offsite, content.commands.createContext("@errands"))
    // A project with an open next-action, so the Projects tab is drivable.
    val project = content.commands.createProject("Launch website")
    content.commands.addSubtask(project, "Draft the launch copy")
    // A filed item so the Reference tab + search have content.
    content.commands.file(content.commands.createTask("Old tax return 2024"))

    embeddedServer(Netty, port = port) {
        // Optionally apply a CSP header to test the phone loopback's policy against Datastar.
        System.getenv("ZYNC_DEV_CSP")?.let { csp ->
            intercept(ApplicationCallPipeline.Plugins) {
                call.response.headers.append("Content-Security-Policy", csp)
            }
        }
        zyncModule(service, content = content, allowUnauthenticatedWeb = true)
    }.start(wait = true)
}

/**
 * Stub producer for the proposals review panel: real `Actor.Agent`-authored ops
 * flagged `proposed`, exactly what the M9 agent runtime will emit — so the
 * accept/reject UX is drivable before that runtime lands.
 */
private fun seedStubProposal(service: SyncService, subject: Ulid) {
    val clock = Clock { System.currentTimeMillis() }
    var counter = 0
    val actor = Actor.Agent("stub")
    fun hlc() = Hlc(clock.nowMillis(), counter++, "dev-agent")
    val proposal = Ulid.generate(clock, Random.Default)
    fun set(field: String, value: JsonElement) = service.ingestLocal(
        Op.SetField(Ulid.generate(clock, Random.Default), proposal, EntityType.Node, hlc(), actor, "dev-agent", clock.nowMillis(), field, value),
    )
    set("kind", JsonPrimitive("comment"))
    set("title", JsonPrimitive("Proposed: split into venue, dates, and invites"))
    set(AgentFlow.FIELD_PROPOSED, JsonPrimitive(true))
    service.ingestLocal(
        Op.Move(Ulid.generate(clock, Random.Default), proposal, EntityType.Node, hlc(), actor, "dev-agent", clock.nowMillis(), subject),
    )
}
