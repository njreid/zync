# zync M1b(1) — Embedded Server & JSON API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Strip the superseded Compose UI, embed a Ktor server in the Android app, and expose the full GTD domain (`NodeRepository`) as a token-guarded JSON API with WebSocket change-push — the foundation the vanilla-JS web UI (next plan) consumes.

**Architecture:** `ZyncServer` wraps Ktor CIO on `127.0.0.1` (ephemeral port), serving static assets via a pluggable lookup (Android `AssetManager` in prod, resources in tests) and `/api/*` routes over the existing `NodeRepository`. Every request requires a loopback token (header, query-once→cookie). Room's InvalidationTracker feeds a WebSocket `/api/events` channel. Spec: `docs/superpowers/specs/2026-07-01-zync-design.md` §8a.

**Tech Stack:** Ktor 3.5.1 (server-core/cio/content-negotiation/websockets/status-pages, test-host), kotlinx-serialization-json 1.11.0, existing Room 2.8.4 domain layer, Robolectric for Android-context tests.

## Global Constraints

- Package `dev.njr.zync`; server code in `dev.njr.zync.server`.
- VCS is **jj** (NOT git): `jj commit -m "<msg>"`, no staging; message trailer (after blank line): `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- All tests JVM (`./gradlew :app:testDebugUnitTest`); Robolectric where Android context is needed (`robolectric.properties` sdk=34 exists).
- Domain layer (Tasks 1–6 of the m1-gtd-core plan) is FROZEN API: do not modify `NodeRepository`/DAOs except where a task explicitly says to add a method.
- All rule violations already throw `IllegalArgumentException` in the repository → API maps them to HTTP 400 `{"error": "<message>"}` via StatusPages.
- Loopback token: server rejects any request lacking the token (401) — via `X-Zync-Token` header, one-time `?token=` query (sets cookie), or `zync_token` cookie.
- Enum wire format: `NodeKind`/`NodeStatus` serialized as their enum names (`"FOLDER"`, `"ACTIVE"`).
- TDD per task: failing test (compile failure counts as RED) → implement → GREEN; full suite before each commit.

---

### Task 1: Strip superseded Compose UI

**Files:**
- Delete: `app/src/main/java/dev/njr/zync/ui/ZyncNavHost.kt`
- Delete: `app/src/test/java/dev/njr/zync/ui/NavigationSmokeTest.kt`
- Modify: `app/src/main/java/dev/njr/zync/MainActivity.kt`
- Modify: `app/build.gradle.kts`, `gradle/libs.versions.toml` (remove Compose)

**Interfaces:**
- Consumes: nothing new.
- Produces: `MainActivity` as a plain `ComponentActivity` placeholder (WebView host arrives in the web-UI plan); a build with no Compose plugin/deps; `ZyncApp` untouched (still provides `repository`).

- [ ] **Step 1: Delete the superseded UI files**

```bash
rm app/src/main/java/dev/njr/zync/ui/ZyncNavHost.kt
rm app/src/test/java/dev/njr/zync/ui/NavigationSmokeTest.kt
rmdir app/src/main/java/dev/njr/zync/ui app/src/test/java/dev/njr/zync/ui 2>/dev/null || true
```
(If Task 7 left a `ui/theme/` package, delete it too.)

- [ ] **Step 2: Replace MainActivity**

`app/src/main/java/dev/njr/zync/MainActivity.kt`:
```kotlin
package dev.njr.zync

import android.os.Bundle
import androidx.activity.ComponentActivity

/** Placeholder host — becomes the WebView host in the M1b web-UI plan. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
```

- [ ] **Step 3: Remove Compose from the build**

In `app/build.gradle.kts`: remove the Compose plugin alias, `buildFeatures { compose = true }` (if present), and all `androidx.compose.*` / `navigation-compose` / `lifecycle-viewmodel-compose` / material-icons dependencies (implementation, debugImplementation ui-test-manifest, testImplementation ui-test-junit4, androidTest compose entries). Keep: `activity` (ktx), Room, Robolectric, coroutines-test, androidx-test-core, junit.
In root `build.gradle.kts`: remove the compose plugin alias if declared there. In `gradle/libs.versions.toml`: remove now-unreferenced compose entries (leave anything still referenced).
If `app/src/androidTest/` contains Compose-dependent template tests, delete them.

- [ ] **Step 4: Verify build + full suite**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; remaining suites (ZyncDatabaseTest 2, NestingRulesTest 3, NodeRepositoryTest 8, ContextFilterTest 3 = 16) all pass.

- [ ] **Step 5: Commit**

```bash
jj commit -m "refactor: strip Compose UI — web UI pivot (spec §8)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Ktor + serialization dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`, root `build.gradle.kts`, `app/build.gradle.kts`

**Interfaces:**
- Produces: catalog aliases `libs.ktor.server.core`, `libs.ktor.server.cio`, `libs.ktor.server.content.negotiation`, `libs.ktor.serialization.kotlinx.json`, `libs.ktor.server.websockets`, `libs.ktor.server.status.pages`, `libs.ktor.server.test.host`, `libs.ktor.client.content.negotiation`, `libs.kotlinx.serialization.json`; kotlin serialization plugin applied.

- [ ] **Step 1: Catalog entries**

Merge into `gradle/libs.versions.toml`:
```toml
[versions]
ktor = "3.5.1"
kotlinxSerialization = "1.11.0"

[libraries]
ktor-server-core = { group = "io.ktor", name = "ktor-server-core", version.ref = "ktor" }
ktor-server-cio = { group = "io.ktor", name = "ktor-server-cio", version.ref = "ktor" }
ktor-server-content-negotiation = { group = "io.ktor", name = "ktor-server-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { group = "io.ktor", name = "ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-server-websockets = { group = "io.ktor", name = "ktor-server-websockets", version.ref = "ktor" }
ktor-server-status-pages = { group = "io.ktor", name = "ktor-server-status-pages", version.ref = "ktor" }
ktor-server-test-host = { group = "io.ktor", name = "ktor-server-test-host", version.ref = "ktor" }
ktor-client-content-negotiation = { group = "io.ktor", name = "ktor-client-content-negotiation", version.ref = "ktor" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }

[plugins]
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```
(`version.ref = "kotlin"` must point at the existing template Kotlin version entry — check its actual key name in the catalog.)

- [ ] **Step 2: Apply**

Root `build.gradle.kts`: `alias(libs.plugins.kotlin.serialization) apply false`. App plugins: `alias(libs.plugins.kotlin.serialization)`. App dependencies:
```kotlin
implementation(libs.ktor.server.core)
implementation(libs.ktor.server.cio)
implementation(libs.ktor.server.content.negotiation)
implementation(libs.ktor.serialization.kotlinx.json)
implementation(libs.ktor.server.websockets)
implementation(libs.ktor.server.status.pages)
implementation(libs.kotlinx.serialization.json)
testImplementation(libs.ktor.server.test.host)
testImplementation(libs.ktor.client.content.negotiation)
```

- [ ] **Step 3: Verify**

Run: `./gradlew :app:assembleDebug :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL. If Ktor 3.5.1's minSdk/JDK requirements clash with the template config, resolve by aligning `compileOptions`/`kotlin.jvmToolchain` — note what changed.

- [ ] **Step 4: Commit**

```bash
jj commit -m "build: add Ktor server stack + kotlinx-serialization

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: ZyncServer skeleton — module, token guard, static assets, error mapping

**Files:**
- Create: `app/src/main/java/dev/njr/zync/server/Dto.kt`
- Create: `app/src/main/java/dev/njr/zync/server/ZyncServer.kt`
- Test: `app/src/test/java/dev/njr/zync/server/ServerAuthTest.kt`
- Test helper: `app/src/test/java/dev/njr/zync/server/TestServer.kt`

**Interfaces:**
- Consumes: `NodeRepository`, `ZyncDatabase` (frozen domain).
- Produces:
  - `@Serializable data class NodeDto(...)` + `fun NodeEntity.toDto(): NodeDto`; `@Serializable data class ContextDto(id: Long, name: String)` + mapper; `@Serializable data class ErrorDto(error: String)`.
  - `fun Application.zyncModule(repo: NodeRepository, token: String, assets: (String) -> Pair<ByteArray, ContentType>?)` — installs ContentNegotiation(json), WebSockets, StatusPages (IllegalArgumentException→400 ErrorDto), token guard, static fallback route. API routes land in Tasks 4–5 inside this module.
  - `class ZyncServer(repo, token, assets, port: Int = 0)` with `start(): Int` (returns bound port), `stop()` — CIO engine on 127.0.0.1.
  - Test helper `zyncTestApplication(block: suspend ApplicationTestBuilder.(NodeRepository, HttpClient) -> Unit)` — Robolectric-compatible: builds `ZyncDatabase.inMemory`, module with token `"test-token"`, a JSON-configured client that sends `X-Zync-Token: test-token` by default, and a no-asset lookup except `/index.html` → `"<html>ok</html>"`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/dev/njr/zync/server/ServerAuthTest.kt`:
```kotlin
package dev.njr.zync.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerAuthTest {

    @Test
    fun `request without token is rejected`() = zyncTestApplication { _, _ ->
        val bare = createClient { }   // no default token header
        assertEquals(HttpStatusCode.Unauthorized, bare.get("/index.html").status)
    }

    @Test
    fun `header token grants access to static assets`() = zyncTestApplication { _, client ->
        val res = client.get("/index.html")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("ok"))
    }

    @Test
    fun `query token sets cookie and cookie works afterwards`() = zyncTestApplication { _, _ ->
        val bare = createClient { install(io.ktor.client.plugins.cookies.HttpCookies) }
        assertEquals(HttpStatusCode.OK, bare.get("/index.html?token=test-token").status)
        assertEquals(HttpStatusCode.OK, bare.get("/index.html").status) // cookie now carries auth
    }

    @Test
    fun `unknown path with valid token is 404`() = zyncTestApplication { _, client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/nope.js").status)
    }
}
```

- [ ] **Step 2: Run to verify RED**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.njr.zync.server.ServerAuthTest"`
Expected: compilation failure.

- [ ] **Step 3: Implement DTOs**

`app/src/main/java/dev/njr/zync/server/Dto.kt`:
```kotlin
package dev.njr.zync.server

import dev.njr.zync.data.ContextEntity
import dev.njr.zync.data.NodeEntity
import dev.njr.zync.data.NodeKind
import dev.njr.zync.data.NodeStatus
import kotlinx.serialization.Serializable

@Serializable
data class NodeDto(
    val id: Long,
    val kind: NodeKind,
    val parentId: Long?,
    val title: String,
    val notes: String,
    val status: NodeStatus,
    val deferUntil: Long?,
    val createdAt: Long,
    val completedAt: Long?,
    val sortOrder: Long,
    val builtin: Boolean,
)

fun NodeEntity.toDto() = NodeDto(
    id, kind, parentId, title, notes, status, deferUntil, createdAt, completedAt, sortOrder, builtin
)

@Serializable
data class ContextDto(val id: Long, val name: String)

fun ContextEntity.toDto() = ContextDto(id, name)

@Serializable
data class ErrorDto(val error: String)
```

- [ ] **Step 4: Implement the server module**

`app/src/main/java/dev/njr/zync/server/ZyncServer.kt`:
```kotlin
package dev.njr.zync.server

import dev.njr.zync.domain.NodeRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json

const val TOKEN_COOKIE = "zync_token"
const val TOKEN_HEADER = "X-Zync-Token"

private fun tokenGuard(token: String) = createApplicationPlugin("ZyncTokenGuard") {
    onCall { call ->
        val presented = call.request.headers[TOKEN_HEADER]
            ?: call.request.queryParameters["token"]
            ?: call.request.cookies[TOKEN_COOKIE]
        if (presented != token) {
            call.respond(HttpStatusCode.Unauthorized, ErrorDto("missing or invalid token"))
        } else if (call.request.queryParameters["token"] == token) {
            call.response.cookies.append(TOKEN_COOKIE, token, path = "/", httpOnly = true)
        }
    }
}

fun Application.zyncModule(
    repo: NodeRepository,
    token: String,
    assets: (String) -> Pair<ByteArray, ContentType>?,
) {
    install(ContentNegotiation) { json(Json { encodeDefaults = true; explicitNulls = true }) }
    install(WebSockets)
    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorDto(cause.message ?: "invalid request"))
        }
    }
    install(tokenGuard(token))
    routing {
        apiRoutes(repo)   // defined in Tasks 4–5; add an empty extension now
        get("/{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/").orEmpty()
                .ifEmpty { "index.html" }
            val hit = assets(path)
            if (hit == null) call.respond(HttpStatusCode.NotFound, ErrorDto("not found"))
            else call.respondBytes(hit.first, hit.second)
        }
    }
}

class ZyncServer(
    private val repo: NodeRepository,
    private val token: String,
    private val assets: (String) -> Pair<ByteArray, ContentType>?,
    private val port: Int = 0,
) {
    private var engine: EmbeddedServer<*, *>? = null

    fun start(): Int {
        val e = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            zyncModule(repo, token, assets)
        }.also { engine = it }
        e.start(wait = false)
        return runCatching {
            kotlinx.coroutines.runBlocking { e.engine.resolvedConnectors().first().port }
        }.getOrElse { port }
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }
}
```
Create the placeholder so Task 3 compiles alone — `apiRoutes` in the same package (it grows in Task 4):
```kotlin
// bottom of ZyncServer.kt for now; Task 4 moves it to ApiRoutes.kt
fun io.ktor.server.routing.Route.apiRoutes(repo: NodeRepository) { /* Task 4 */ }
```

- [ ] **Step 5: Implement the test helper**

`app/src/test/java/dev/njr/zync/server/TestServer.kt`:
```kotlin
package dev.njr.zync.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.njr.zync.data.ZyncDatabase
import dev.njr.zync.domain.NodeRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

fun zyncTestApplication(
    block: suspend ApplicationTestBuilder.(NodeRepository, HttpClient) -> Unit,
) {
    val db = ZyncDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
    val repo = NodeRepository(db)
    try {
        testApplication {
            application {
                zyncModule(repo, token = "test-token", assets = { path ->
                    if (path == "index.html")
                        "<html>ok</html>".toByteArray() to ContentType.Text.Html
                    else null
                })
            }
            val client = createClient {
                install(ContentNegotiation) { json() }
                defaultRequest { headers.append(TOKEN_HEADER, "test-token") }
            }
            block(repo, client)
        }
    } finally {
        db.close()
    }
}
```

- [ ] **Step 6: GREEN + full suite**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.njr.zync.server.ServerAuthTest"` → 4 PASS.
Run: `./gradlew :app:testDebugUnitTest` → all pass (16 + 4).

Ktor API note: exact plugin/callback names drift between Ktor minor versions — if `createApplicationPlugin`/`resolvedConnectors` signatures differ under 3.5.1, adapt to the current API preserving behavior (guard every route incl. static; query-token sets cookie) and note it in the report.

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat: embedded Ktor server skeleton — token guard, static assets, error mapping

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Nodes API — CRUD + clarify operations

**Files:**
- Create: `app/src/main/java/dev/njr/zync/server/ApiRoutes.kt` (move the `apiRoutes` stub here)
- Test: `app/src/test/java/dev/njr/zync/server/NodesApiTest.kt`

**Interfaces:**
- Consumes: `zyncTestApplication`, `NodeRepository` (frozen), DTOs.
- Produces routes (all under the token guard, JSON in/out):
  - `GET /api/roots` → `[NodeDto]` (repo.observeRoots first emission)
  - `GET /api/nodes/{id}` → `NodeDto` | 404
  - `GET /api/nodes/{id}/children` → `[NodeDto]`
  - `POST /api/inbox` `{"title": "..."}` → 201 `NodeDto` (quickAddTask)
  - `POST /api/nodes` `{"kind","parentId","title"}` → 201 `NodeDto`
  - `PATCH /api/nodes/{id}` `{"title"?, "notes"?}` → 200 `NodeDto`
  - `POST /api/nodes/{id}/defer` `{"until": Long?}` → 200 `NodeDto`
  - `POST /api/nodes/{id}/move` `{"parentId": Long}` → 200 `NodeDto`
  - `POST /api/nodes/{id}/convert` `{"folderId": Long}` → 200 `NodeDto`
  - `POST /api/nodes/{id}/complete` | `/reopen` | `/trash` → 200 `NodeDto`
  - Request bodies as `@Serializable` data classes in `ApiRoutes.kt`: `TitleBody(title)`, `CreateNodeBody(kind, parentId, title)`, `PatchNodeBody(title: String? = null, notes: String? = null)`, `DeferBody(until: Long?)`, `MoveBody(parentId: Long)`, `ConvertBody(folderId: Long)`.
  - Rule violations → 400 `{"error"}` (StatusPages, already wired); unknown id on GET → 404.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/njr/zync/server/NodesApiTest.kt`:
```kotlin
package dev.njr.zync.server

import dev.njr.zync.data.NodeKind
import dev.njr.zync.data.NodeStatus
import dev.njr.zync.data.ZyncDatabase
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NodesApiTest {

    @Test
    fun `roots lists seeded builtin folders`() = zyncTestApplication { _, client ->
        val roots: List<NodeDto> = client.get("/api/roots").body()
        assertEquals(listOf("Inbox", "Someday"), roots.map { it.title })
        assertTrue(roots.all { it.builtin && it.kind == NodeKind.FOLDER })
    }

    @Test
    fun `quick add creates task in inbox`() = zyncTestApplication { _, client ->
        val res = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("buy milk"))
        }
        assertEquals(HttpStatusCode.Created, res.status)
        val dto: NodeDto = res.body()
        assertEquals(ZyncDatabase.INBOX_ID, dto.parentId)
        val children: List<NodeDto> = client.get("/api/nodes/${ZyncDatabase.INBOX_ID}/children").body()
        assertEquals(listOf("buy milk"), children.map { it.title })
    }

    @Test
    fun `create move convert complete trash roundtrip`() = zyncTestApplication { _, client ->
        suspend fun create(kind: NodeKind, parentId: Long?, title: String): NodeDto =
            client.post("/api/nodes") {
                contentType(ContentType.Application.Json)
                setBody(CreateNodeBody(kind, parentId, title))
            }.body()

        val folder = create(NodeKind.FOLDER, null, "Work")
        val project = create(NodeKind.PROJECT, folder.id, "Site")
        val task: NodeDto = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("write copy"))
        }.body()

        val moved: NodeDto = client.post("/api/nodes/${task.id}/move") {
            contentType(ContentType.Application.Json); setBody(MoveBody(project.id))
        }.body()
        assertEquals(project.id, moved.parentId)

        val second: NodeDto = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("plan party"))
        }.body()
        val converted: NodeDto = client.post("/api/nodes/${second.id}/convert") {
            contentType(ContentType.Application.Json); setBody(ConvertBody(folder.id))
        }.body()
        assertEquals(NodeKind.PROJECT, converted.kind)
        assertEquals(folder.id, converted.parentId)

        val done: NodeDto = client.post("/api/nodes/${task.id}/complete").body()
        assertEquals(NodeStatus.DONE, done.status)
        val reopened: NodeDto = client.post("/api/nodes/${task.id}/reopen").body()
        assertEquals(NodeStatus.ACTIVE, reopened.status)
        assertNull(reopened.completedAt)
        val trashed: NodeDto = client.post("/api/nodes/${task.id}/trash").body()
        assertEquals(NodeStatus.DROPPED, trashed.status)
    }

    @Test
    fun `patch edits title and notes`() = zyncTestApplication { _, client ->
        val task: NodeDto = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("draft"))
        }.body()
        val patched: NodeDto = client.patch("/api/nodes/${task.id}") {
            contentType(ContentType.Application.Json)
            setBody(PatchNodeBody(title = "final", notes = "details"))
        }.body()
        assertEquals("final", patched.title)
        assertEquals("details", patched.notes)
    }

    @Test
    fun `defer sets and clears`() = zyncTestApplication { _, client ->
        val task: NodeDto = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("later"))
        }.body()
        val deferred: NodeDto = client.post("/api/nodes/${task.id}/defer") {
            contentType(ContentType.Application.Json); setBody(DeferBody(99999L))
        }.body()
        assertEquals(99999L, deferred.deferUntil)
        val cleared: NodeDto = client.post("/api/nodes/${task.id}/defer") {
            contentType(ContentType.Application.Json); setBody(DeferBody(null))
        }.body()
        assertNull(cleared.deferUntil)
    }

    @Test
    fun `rule violations map to 400 with message`() = zyncTestApplication { _, client ->
        val res = client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody(CreateNodeBody(NodeKind.TASK, null, "root task"))
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        val err: ErrorDto = res.body()
        assertTrue(err.error.isNotBlank())
        assertEquals(HttpStatusCode.BadRequest,
            client.post("/api/nodes/${ZyncDatabase.INBOX_ID}/trash").status)
    }

    @Test
    fun `get unknown node is 404`() = zyncTestApplication { _, client ->
        assertEquals(HttpStatusCode.NotFound, client.get("/api/nodes/9999").status)
    }
}
```

- [ ] **Step 2: RED**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.njr.zync.server.NodesApiTest"`
Expected: compilation failure (bodies/routes missing).

- [ ] **Step 3: Implement**

`app/src/main/java/dev/njr/zync/server/ApiRoutes.kt` (delete the stub from ZyncServer.kt):
```kotlin
package dev.njr.zync.server

import dev.njr.zync.data.NodeKind
import dev.njr.zync.domain.NodeRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable data class TitleBody(val title: String)
@Serializable data class CreateNodeBody(val kind: NodeKind, val parentId: Long?, val title: String)
@Serializable data class PatchNodeBody(val title: String? = null, val notes: String? = null)
@Serializable data class DeferBody(val until: Long?)
@Serializable data class MoveBody(val parentId: Long)
@Serializable data class ConvertBody(val folderId: Long)

fun Route.apiRoutes(repo: NodeRepository) {
    route("/api") {
        get("/roots") { call.respond(repo.observeRoots().first().map { it.toDto() }) }

        post("/inbox") {
            val id = repo.quickAddTask(call.receive<TitleBody>().title)
            call.respond(HttpStatusCode.Created, repo.get(id)!!.toDto())
        }

        post("/nodes") {
            val b = call.receive<CreateNodeBody>()
            val id = repo.createNode(b.kind, b.parentId, b.title)
            call.respond(HttpStatusCode.Created, repo.get(id)!!.toDto())
        }

        route("/nodes/{id}") {
            get {
                val node = repo.get(id()) ?: return@get call.respond(
                    HttpStatusCode.NotFound, ErrorDto("no such node"))
                call.respond(node.toDto())
            }
            get("/children") {
                call.respond(repo.observeChildren(id()).first().map { it.toDto() })
            }
            patch {
                val b = call.receive<PatchNodeBody>()
                b.title?.let { repo.rename(id(), it) }
                b.notes?.let { repo.setNotes(id(), it) }
                call.respond(repo.get(id())!!.toDto())
            }
            post("/defer") {
                repo.setDefer(id(), call.receive<DeferBody>().until)
                call.respond(repo.get(id())!!.toDto())
            }
            post("/move") {
                repo.move(id(), call.receive<MoveBody>().parentId)
                call.respond(repo.get(id())!!.toDto())
            }
            post("/convert") {
                repo.convertTaskToProject(id(), call.receive<ConvertBody>().folderId)
                call.respond(repo.get(id())!!.toDto())
            }
            post("/complete") { repo.complete(id()); call.respond(repo.get(id())!!.toDto()) }
            post("/reopen") { repo.reopen(id()); call.respond(repo.get(id())!!.toDto()) }
            post("/trash") { repo.trash(id()); call.respond(repo.get(id())!!.toDto()) }
        }
    }
}

private fun io.ktor.server.routing.RoutingContext.id(): Long =
    requireNotNull(call.parameters["id"]?.toLongOrNull()) { "invalid id" }
```
(If `RoutingContext` isn't the receiver type in Ktor 3.5.1 route lambdas, adapt the `id()` helper to the actual receiver — e.g. a top-level `fun ApplicationCall.id()` — preserving behavior.)

- [ ] **Step 4: GREEN + full suite**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.njr.zync.server.NodesApiTest"` → 7 PASS.
Run: `./gradlew :app:testDebugUnitTest` → all pass.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: nodes JSON API — CRUD and GTD clarify operations

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Contexts API, destinations, WebSocket change-push

**Files:**
- Modify: `app/src/main/java/dev/njr/zync/server/ApiRoutes.kt`
- Modify: `app/src/main/java/dev/njr/zync/data/NodeDao.kt` (add `observeDestinations`)
- Modify: `app/src/main/java/dev/njr/zync/domain/NodeRepository.kt` (add `observeDestinations`)
- Modify: `app/src/main/java/dev/njr/zync/server/ZyncServer.kt` (wire events route needs the database — change `zyncModule`/`ZyncServer` to also take `db: ZyncDatabase`)
- Test: `app/src/test/java/dev/njr/zync/server/ContextsApiTest.kt`

**Interfaces:**
- Produces routes:
  - `GET /api/contexts` → `[ContextDto]`; `POST /api/contexts` `{"name"}` → 201 `ContextDto`
  - `GET /api/contexts/{id}/tasks` → `[NodeDto]` (recursive filter incl. defer)
  - `GET /api/nodes/{id}/contexts` → `[ContextDto]`; `PUT /api/nodes/{id}/contexts` `{"contextIds":[...]}` → 200 `[ContextDto]`
  - `GET /api/destinations` → `[NodeDto]` (active folders+projects, folders first) — new DAO query:
    `SELECT * FROM node WHERE kind IN ('FOLDER','PROJECT') AND status = 'ACTIVE' ORDER BY kind, title` as `observeDestinations(): Flow<List<NodeEntity>>`, exposed on the repository.
  - `WS /api/events` — on connect sends `{"type":"hello"}`; whenever tables `node`/`context`/`node_context` invalidate, sends `{"type":"changed"}` (debounced 100 ms). Implement with `db.invalidationTracker` wrapped in a `callbackFlow` (register `InvalidationTracker.Observer` on those tables, `trySend`, unregister in `awaitClose`), `.debounce(100)`.
  - `zyncTestApplication` gains the db handle: `block(db, repo, client)` — update Task 3/4 call sites' lambda signatures.
- Body: `@Serializable data class ContextIdsBody(val contextIds: List<Long>)`, `@Serializable data class NameBody(val name: String)`, `@Serializable data class EventDto(val type: String)`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/njr/zync/server/ContextsApiTest.kt`:
```kotlin
package dev.njr.zync.server

import dev.njr.zync.data.NodeKind
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContextsApiTest {

    @Test
    fun `create context tag task and filter recursively`() = zyncTestApplication { _, _, client ->
        val ctx: ContextDto = client.post("/api/contexts") {
            contentType(ContentType.Application.Json); setBody(NameBody("groceries"))
        }.body()
        val task: NodeDto = client.post("/api/inbox") {
            contentType(ContentType.Application.Json); setBody(TitleBody("buy milk"))
        }.body()
        val tagged: List<ContextDto> = client.put("/api/nodes/${task.id}/contexts") {
            contentType(ContentType.Application.Json); setBody(ContextIdsBody(listOf(ctx.id)))
        }.body()
        assertEquals(listOf("groceries"), tagged.map { it.name })
        val inContext: List<NodeDto> = client.get("/api/contexts/${ctx.id}/tasks").body()
        assertEquals(listOf("buy milk"), inContext.map { it.title })
    }

    @Test
    fun `destinations lists folders then projects`() = zyncTestApplication { _, _, client ->
        val folder: NodeDto = client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody(CreateNodeBody(NodeKind.FOLDER, null, "Work"))
        }.body()
        client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody(CreateNodeBody(NodeKind.PROJECT, folder.id, "Site"))
        }
        val dest: List<NodeDto> = client.get("/api/destinations").body()
        assertEquals(listOf("Inbox", "Someday", "Work", "Site"), dest.map { it.title })
    }

    @Test
    fun `websocket pushes changed event on mutation`() = zyncTestApplication { _, _, client ->
        val ws = createClient {
            install(WebSockets)
        }
        ws.webSocket("/api/events?token=test-token") {
            val hello = (incoming.receive() as Frame.Text).readText()
            assertTrue(hello.contains("hello"))
            client.post("/api/inbox") {
                contentType(ContentType.Application.Json); setBody(TitleBody("trigger"))
            }
            withTimeout(5_000) {
                val evt = (incoming.receive() as Frame.Text).readText()
                assertTrue(evt.contains("changed"))
            }
        }
    }
}
```

- [ ] **Step 2: RED**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.njr.zync.server.ContextsApiTest"`
Expected: compilation failure.

- [ ] **Step 3: Implement**

DAO (`NodeDao.kt`) — add:
```kotlin
    @Query("SELECT * FROM node WHERE kind IN ('FOLDER','PROJECT') AND status = 'ACTIVE' ORDER BY kind, title")
    fun observeDestinations(): Flow<List<NodeEntity>>
```
Repository (`NodeRepository.kt`) — add:
```kotlin
    fun observeDestinations(): Flow<List<NodeEntity>> = dao.observeDestinations()
```
`zyncModule`/`ZyncServer`: add `db: ZyncDatabase` parameter (before `token`); update `TestServer.kt` to pass it and widen the helper lambda to `(ZyncDatabase, NodeRepository, HttpClient)`; fix the Task 3/4 tests' lambda arity (`{ _, _, client -> }`).

`ApiRoutes.kt` — add inside `route("/api")` (signature becomes `fun Route.apiRoutes(db: ZyncDatabase, repo: NodeRepository)`):
```kotlin
@Serializable data class NameBody(val name: String)
@Serializable data class ContextIdsBody(val contextIds: List<Long>)
@Serializable data class EventDto(val type: String)

        get("/contexts") { call.respond(repo.observeContexts().first().map { it.toDto() }) }
        post("/contexts") {
            val id = repo.createContext(call.receive<NameBody>().name)
            val ctx = repo.observeContexts().first().first { it.id == id }
            call.respond(HttpStatusCode.Created, ctx.toDto())
        }
        get("/contexts/{id}/tasks") {
            call.respond(repo.observeTasksInContext(id()).first().map { it.toDto() })
        }
        get("/destinations") {
            call.respond(repo.observeDestinations().first().map { it.toDto() })
        }
        // under route("/nodes/{id}"):
            get("/contexts") {
                call.respond(repo.observeContextsFor(id()).first().map { it.toDto() })
            }
            put("/contexts") {
                repo.setContexts(id(), call.receive<ContextIdsBody>().contextIds.toSet())
                call.respond(repo.observeContextsFor(id()).first().map { it.toDto() })
            }
        // websocket route (needs io.ktor.server.websocket.webSocket + sendSerialized or manual Frame.Text):
        webSocket("/events") {
            sendSerialized(EventDto("hello"))   // or send(Frame.Text("""{"type":"hello"}"""))
            db.changesFlow().collect { sendSerialized(EventDto("changed")) }
        }
```
`changesFlow` helper in `ZyncServer.kt`:
```kotlin
@OptIn(kotlinx.coroutines.FlowPreview::class)
fun ZyncDatabase.changesFlow(): kotlinx.coroutines.flow.Flow<Unit> =
    kotlinx.coroutines.flow.callbackFlow {
        val observer = object : androidx.room.InvalidationTracker.Observer(
            arrayOf("node", "context", "node_context")
        ) {
            override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
        }
        invalidationTracker.addObserver(observer)
        awaitClose { invalidationTracker.removeObserver(observer) }
    }.debounce(100)
```
(For WebSocket serialization either install `contentConverter` on the WebSockets plugin for `sendSerialized`, or just send `Frame.Text` with literal JSON — pick whichever compiles cleanly and note it.)

- [ ] **Step 4: GREEN + full suite**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.njr.zync.server.ContextsApiTest"` → 3 PASS.
Run: `./gradlew :app:testDebugUnitTest` → all pass (Task 3/4 tests updated to new lambda arity).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: contexts/destinations API + WebSocket change-push

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-Review Notes

- **Spec coverage (§8a):** static assets (T3), JSON API over NodeRepository (T4–T5), WebSocket push (T5), loopback token incl. cookie hand-off (T3), 127.0.0.1 binding (T3 `ZyncServer`). Server *lifecycle wiring into the app* (start with app process, WebView host) intentionally lands in the web-UI plan where it's testable end-to-end.
- **Known API-drift risk:** Ktor 3.5.1 exact signatures (plugin DSL, RoutingContext receiver, sendSerialized) — tasks carry explicit "adapt preserving behavior" notes.
- **Type consistency check:** `zyncTestApplication` changes arity in T5 (documented, with instruction to fix T3/T4 call sites); `apiRoutes(db, repo)` signature change likewise. DTO/body names consistent across T3–T5.
