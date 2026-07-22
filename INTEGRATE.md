# Integrating with Zync — the external op API

Scripts, bots, and services write to Zync through one door: **`POST /api/ops`**. You send a
small JSON *envelope* of high-level intents (create an item, comment, set a field, tag, move,
complete, trash); the server translates them into op-log operations, stamps them with your
bot's provenance (`Actor.Bot(<id>)`), and ingests the whole envelope atomically. You never
speak the raw op-log protocol, mint clocks, or sign requests.

Two guarantees worth knowing up front:

- **Provenance is server-assigned.** Your writes are attributed to your bot; you can't author
  as a human.
- **A bot can never silently overwrite a human's edit.** The merge layer keeps a human's field
  value regardless of timing. If you want a human to review a change, use **propose mode** and
  it becomes a suggestion the user accepts or dismisses.

> Typed, dependency-light **Kotlin** and **Go** SDKs live in [`sdk/`](sdk/README.md) — they
> wrap exactly what's shown below (`sdk/kotlin`, `sdk/go`). Use them for the ergonomic path;
> the standard-library examples here show what they do under the hood, and work with no SDK
> at all.

---

## 1. Setup

**On the server**, set a bot token (single-token mode; the multi-bot registry comes later):

```sh
export ZYNC_BOT_TOKEN="a-long-random-secret"   # required; unset ⇒ the op API is closed
export ZYNC_BOT_ID="my-bot"                    # optional; default "bot" — appears as provenance
```

**In your client**, you need:

- the **base URL** of the Zync server (e.g. `https://zync.example`; locally `http://127.0.0.1:8080`),
- the **bearer token** (`ZYNC_BOT_TOKEN` above), sent as `Authorization: Bearer <token>`.

---

## 2. The envelope (the whole contract)

```jsonc
POST /api/ops
Authorization: Bearer <token>
Content-Type: application/json

{
  "idempotencyKey": "a-uuid",     // optional; a retry with the same key returns the first result
  "mode": "commit",               // optional; "commit" (default) or "propose"
  "intents": [ /* 1..200 intents, applied atomically */ ]
}
```

Each **intent** is one object; `op` picks the verb and the rest are its arguments:

| `op` | arguments | effect |
|------|-----------|--------|
| `create` | `title`, `kind` (`task`\|`project`, default task), `parent`, `fields`, `tags` | new item |
| `comment` | `target`, `text` | a comment child (always commits) |
| `setField` | `target`, `field`, `value` | set a field (e.g. `notes`, `dueDate`, `person`, `size`) |
| `addTag` | `target`, `context` | tag with a context id |
| `move` | `target`, `parent` | reparent |
| `complete` | `target` | mark done |
| `trash` | `target` | mark dropped |

- `parent`/`target`/`context` take a node **ULID**, and `parent` also accepts the aliases
  `"inbox"` or `"reference"`.
- `value` is any JSON value (string/number/bool).
- `attach` (media/blobs) is coming with `PUT /api/blobs` in a later step.

**Response** — one result per intent, in order:

```json
{ "results": [ { "op": "create", "nodeId": "01J…", "status": "committed" } ] }
```

`status` is `committed`, `proposed`, or `error` (with an `error` message). If **any** intent
is `error`, the whole envelope is rejected (HTTP 400) and **nothing** is ingested — envelopes
are all-or-nothing.

**Propose mode** (`"mode": "propose"`, or any bot without commit capability): `create` becomes
a pending proposal, and `setField`/`complete`/`trash` become **suggestions** a human accepts or
dismisses (status `proposed`). `comment` still commits.

---

## 3. Kotlin (standard library — `java.net.http`)

Zero extra dependencies; JDK 11+. Build the JSON with your serializer of choice
(`kotlinx.serialization` shown; a hand-built string works too).

```kotlin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

class ZyncClient(private val baseUrl: String, private val token: String) {
    private val http = HttpClient.newHttpClient()

    /** Submit a raw envelope JSON body; returns (status, body). */
    fun submit(envelopeJson: String): Pair<Int, String> {
        val req = HttpRequest.newBuilder(URI.create("$baseUrl/api/ops"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(envelopeJson))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        return resp.statusCode() to resp.body()
    }
}

fun main() {
    val zync = ZyncClient(baseUrl = "https://zync.example", token = System.getenv("ZYNC_BOT_TOKEN"))

    // Create a task in the inbox, with a note and a due date.
    val (status, body) = zync.submit(
        """
        {
          "idempotencyKey": "${UUID.randomUUID()}",
          "intents": [
            { "op": "create", "title": "Read: Fractional indexing",
              "parent": "inbox",
              "fields": { "notes": "https://example.com/article" } }
          ]
        }
        """.trimIndent(),
    )
    println("HTTP $status: $body")   // HTTP 200: {"results":[{"op":"create","nodeId":"01J…","status":"committed"}]}
}
```

**Or just use the SDK.** [`sdk/kotlin`](sdk/README.md) (`:sdk:kotlin`) wraps all of this with
typed envelopes (reusing the `dev.njr.zync.core.api` wire types) and convenience verbs, so the
above becomes:

```kotlin
val zync = ZyncClient(baseUrl = "https://zync.example", token = System.getenv("ZYNC_BOT_TOKEN"))
val r = zync.create(title = "Read: Fractional indexing", parent = "inbox",
                    fields = mapOf("notes" to JsonPrimitive("https://example.com/article")))
zync.propose(target = r.nodeId!!, field = "dueDate", value = JsonPrimitive(1893456000000))
```

**Propose a change for human review:**

```kotlin
zync.submit(
    """{ "mode": "propose",
         "intents": [ { "op": "setField", "target": "01J…", "field": "dueDate", "value": 1893456000000 } ] }""",
)
// → {"results":[{"op":"setField","nodeId":"<suggestion id>","status":"proposed"}]}
```

---

## 4. Go (standard library — `net/http` + `encoding/json`)

No third-party dependencies.

```go
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"time"

	"github.com/google/uuid" // or any UUID source; stdlib crypto/rand also works
)

type Intent struct {
	Op     string                 `json:"op"`
	Kind   string                 `json:"kind,omitempty"`
	Title  string                 `json:"title,omitempty"`
	Parent string                 `json:"parent,omitempty"`
	Target string                 `json:"target,omitempty"`
	Field  string                 `json:"field,omitempty"`
	Value  any                    `json:"value,omitempty"`
	Text   string                 `json:"text,omitempty"`
	Fields map[string]any         `json:"fields,omitempty"`
	Tags   []string               `json:"tags,omitempty"`
}

type Envelope struct {
	IdempotencyKey string   `json:"idempotencyKey,omitempty"`
	Mode           string   `json:"mode,omitempty"`
	Intents        []Intent `json:"intents"`
}

type Result struct {
	Results []struct {
		Op     string `json:"op"`
		NodeID string `json:"nodeId"`
		Status string `json:"status"`
		Error  string `json:"error"`
	} `json:"results"`
}

type Client struct {
	BaseURL, Token string
	HTTP           *http.Client
}

func (c *Client) Submit(ctx context.Context, env Envelope) (*Result, error) {
	if env.IdempotencyKey == "" {
		env.IdempotencyKey = uuid.NewString()
	}
	body, _ := json.Marshal(env)
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, c.BaseURL+"/api/ops", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+c.Token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	var out Result
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	if resp.StatusCode >= 300 {
		return &out, fmt.Errorf("zync: HTTP %d", resp.StatusCode)
	}
	return &out, nil
}

func main() {
	c := &Client{BaseURL: "https://zync.example", Token: os.Getenv("ZYNC_BOT_TOKEN"), HTTP: &http.Client{Timeout: 10 * time.Second}}
	res, err := c.Submit(context.Background(), Envelope{
		Intents: []Intent{
			{Op: "create", Title: "Read: Fractional indexing", Parent: "inbox",
				Fields: map[string]any{"notes": "https://example.com/article"}},
		},
	})
	if err != nil {
		panic(err)
	}
	fmt.Printf("%+v\n", res.Results) // [{Op:create NodeID:01J… Status:committed Error:}]
}
```

**Or just use the SDK.** [`sdk/go`](sdk/README.md) provides these types + a
`zync.New(baseURL, token)` client with `Create`, `Comment`, `SetField`, `Propose`,
`UploadBlob`, and idempotency handled for you — all on `net/http`/`encoding/json`, no
third-party deps:

```go
c := zync.New("https://zync.example", os.Getenv("ZYNC_BOT_TOKEN"))
r, _ := c.Create(ctx, "Read: Fractional indexing", "inbox")
_, _ = c.Propose(ctx, r.NodeID, "dueDate", 1893456000000)
```

---

## 5. Raw REST (curl)

**Create an item:**

```sh
curl -sS -X POST "$BASE/api/ops" \
  -H "Authorization: Bearer $ZYNC_BOT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "11111111-1111-1111-1111-111111111111",
    "intents": [
      { "op": "create", "title": "Buy oat milk", "parent": "inbox" }
    ]
  }'
# {"results":[{"op":"create","nodeId":"01J…","status":"committed"}]}
```

**Comment + set a field on an existing item (atomic batch):**

```sh
curl -sS -X POST "$BASE/api/ops" \
  -H "Authorization: Bearer $ZYNC_BOT_TOKEN" -H "Content-Type: application/json" \
  -d '{ "intents": [
        { "op": "comment",  "target": "01J…", "text": "Auto-summary: …" },
        { "op": "setField", "target": "01J…", "field": "person", "value": "Sam" }
      ] }'
```

**Propose a change for a human to accept:**

```sh
curl -sS -X POST "$BASE/api/ops" \
  -H "Authorization: Bearer $ZYNC_BOT_TOKEN" -H "Content-Type: application/json" \
  -d '{ "mode": "propose",
        "intents": [ { "op": "setField", "target": "01J…", "field": "dueDate", "value": 1893456000000 } ] }'
# {"results":[{"op":"setField","nodeId":"<suggestion id>","status":"proposed"}]}
```

### Conventions & gotchas

- **Auth:** `Authorization: Bearer <ZYNC_BOT_TOKEN>`. Missing/wrong ⇒ `401`.
- **Atomicity:** an envelope of up to 200 intents applies all-or-nothing. One bad intent ⇒
  `400` and nothing is written; fix it and resend.
- **Idempotency:** set `idempotencyKey` (a UUID). Resending the same key returns the original
  result and writes nothing new — safe to retry on network errors.
- **Parents:** `"inbox"` and `"reference"` are aliases; otherwise pass a node ULID.
- **Human safety:** committed bot writes never overwrite a human-authored field value. To
  suggest a change instead of forcing it, use `"mode": "propose"`.
- **Statuses:** `committed` (live), `proposed` (awaiting human accept/dismiss), `error`.

---

*This document tracks the API as implemented; see `docs/superpowers/specs/2026-07-22-external-op-api.md`
for the full design, the capability/registry model, `/api/blobs`, the `/api/changes` feed, and
the SDKs in [`sdk/`](sdk/README.md).*
