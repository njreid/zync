# Zync MCP interface

A **stateless** [Model Context Protocol](https://modelcontextprotocol.io) endpoint so MCP
clients (Claude Desktop/Code, agent runtimes, IDEs) can *read* your Zync content and *propose*
changes to it. Design: [`docs/superpowers/specs/2026-08-11-stateless-mcp-interface.md`](superpowers/specs/2026-08-11-stateless-mcp-interface.md).

**Everything the MCP door writes is a proposal.** Every mutation tool is submitted in `propose`
mode regardless of the bot's own capability, so an LLM can only surface suggestions/flagged nodes
that *you* accept or dismiss in the proposals panel. Combined with the `Actor.Bot` "human beats
bot" merge rule, an LLM can never silently overwrite a human decision.

## Transport

- **`POST /mcp`** — one JSON-RPC 2.0 message in, one JSON response out. No session id, no
  server-initiated stream. Content type `application/json`.
- **`GET /mcp`** → `405` (a stateless server has no server→client channel; poll read tools, or
  use the bearer-authed `GET /api/changes` SSE feed to know when to re-query).
- Methods: `initialize`, `ping`, `tools/list`, `tools/call`, `resources/list`,
  `resources/read`, `resources/templates/list`. Notifications (e.g. `notifications/initialized`)
  are accepted and get an empty `202`.

## Auth

Bearer token, exactly like `/api/ops`: `Authorization: Bearer <token>`. Mint one with
`server bot add <name>` (see the external-op-api spec). The bot's `verbs` capability gates which
write tools appear in `tools/list`; `fields` scopes `set_field`; rate limits apply. Only the
commit-vs-propose direction is overridden — always propose.

## Tools

**Read** (always available): `list_inbox`, `list_children`, `get_node`, `search`,
`list_comments`, `list_proposals`, `list_reference`.

**Write** (gated by capability verb; all proposed): `create_task`, `create_project`, `set_field`,
`complete`, `trash`, `comment`\*, `add_free_tag`\*, `remove_free_tag`\*, `move`, `add_tag`,
`attach`. \*Additive verbs (comment, free-tags) can't clobber human state, so they commit.

`attach` references a blob uploaded first via `PUT /api/blobs`.

## Resources

- Static: `zync://inbox`, `zync://proposals`, `zync://reference`.
- Templates: `zync://node/{id}`, `zync://node/{id}/comments`.

`resources/read` returns `application/json` node projections.

## Example

```bash
# initialize
curl -sS $ZYNC/mcp -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'

# list tools
curl -sS $ZYNC/mcp -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# propose a due date (mints a suggestion for you to confirm)
curl -sS $ZYNC/mcp -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call",
       "params":{"name":"set_field","arguments":{"id":"<ulid>","field":"dueDate","value":1893456000000}}}'
```
