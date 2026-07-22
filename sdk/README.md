# Zync SDKs

Typed, standard-library-first clients for the Zync external op API. Both wrap the same
`POST /api/ops` envelope contract (see [`../INTEGRATE.md`](../INTEGRATE.md) and the spec in
`docs/superpowers/specs/2026-07-22-external-op-api.md`).

| Language | Path | Deps |
|----------|------|------|
| Kotlin   | [`kotlin/`](kotlin) (`:sdk:kotlin`) | `java.net.http` + `kotlinx.serialization`; reuses the wire types in `:core` (`dev.njr.zync.core.api`) |
| Go       | [`go/`](go) | none — `net/http` + `encoding/json` only |

Both auto-generate an idempotency key per submit (retries are safe), map non-2xx responses
to a typed error, and expose convenience verbs (`create`, `comment`, `setField`, `propose`,
`complete`, `trash`, `move`, `addTag`, `uploadBlob`, `attach`).

## Kotlin

```kotlin
val zync = ZyncClient(baseUrl = "https://zync.example", token = System.getenv("ZYNC_BOT_TOKEN"))
val r = zync.create(title = "Read: Fractional indexing", parent = "inbox",
                    fields = mapOf("notes" to JsonPrimitive("https://example.com/article")))
println(r.nodeId)                       // committed
zync.propose(target = r.nodeId!!, field = "dueDate", value = JsonPrimitive(1893456000000))
```

Run its tests: `./gradlew :sdk:kotlin:test`.

## Go

```go
c := zync.New("https://zync.example", os.Getenv("ZYNC_BOT_TOKEN"))
r, _ := c.Create(ctx, "Read: Fractional indexing", "inbox")
_, _ = c.Propose(ctx, r.NodeID, "dueDate", 1893456000000)
```

Run its tests: `cd sdk/go && go test ./...`.

## Testing

Each SDK has a fast unit suite that runs against an **in-process HTTP stub**
(`com.sun.net.httpserver` / `net/http/httptest`) — no server needed. These assert the client
speaks the wire format we wrote down, but by design they'd stay green if that format ever
drifted from what the server accepts.

Closing that gap are two **end-to-end** tests that drive a **real `zyncd`**:

- **Kotlin** — `SdkE2ETest` in `:server` boots a real Netty server on an ephemeral port and
  drives it with the real `ZyncClient` over a TCP socket. Runs in-JVM as part of
  `./gradlew :server:test` (no external process).
- **Go** — `sdk/go/e2e_test.go` (build tag `e2e`, skipped by a plain `go test ./...`) hits a
  running server. Run the whole loop — build server, boot it, drive it, tear down — with:

  ```sh
  sdk/smoke_e2e.sh
  ```
