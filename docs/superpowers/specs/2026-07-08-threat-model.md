# zync — Threat Model (trusted server)

> **Status: 🟢 (2026-07-08).** The rebuild introduces an internet-facing, always-on
> server that **holds plaintext** (trusted-server model — not zero-knowledge). This
> is a new, significant attack surface; this doc names the assets, trust boundaries,
> threats, mitigations, and **accepted residual risks**. Feeds M4 (baseline) and M9
> (hardening). Security tests are enumerated in `2026-07-08-test-strategy.md §6`.

## Assets
- The user's **GTD data** (plaintext on the server + on the phone replica).
- **Attachment blobs** (S3).
- **Device keys** (Ed25519), **browser session**, **LLM API keys**, **AWS creds**
  (instance role), the **server host** itself, and **backups** (litestream in S3).

## Trust boundaries & model
- **Trusted:** the server host, the phone, the user's desktop/browser session.
- **Untrusted:** the network, the public internet, **all task/attachment content fed
  to operators** (may be attacker-influenced), third-party deps, and anything outside
  the device-auth boundary.
- **Explicitly not zero-knowledge:** the server sees plaintext (to run operators).
  Consequence accepted below.

## Threats → mitigations → residual

| # | Threat | Mitigation | Residual |
|---|---|---|---|
| T1 | **Unauthorized API access** (spoofing) | Device Ed25519 signed requests + browser session; reject unknown devices; TLS-only; no anon endpoints except `/health` + ACME; per-device rate limits | Low |
| T2 | **Network MITM / interception** | Real Let's Encrypt TLS (no self-signed pinning needed now); HSTS | Low |
| T3 | **Server compromise = total disclosure** (live root ⇒ plaintext data + keys) | Minimize surface: small daemon, patched host, **SSM-only (no inbound SSH)**, SG open only 443/ACME, least-privilege IAM; EBS + S3 **SSE** at rest (defense-in-depth vs. disk theft, not live compromise); monitoring/alerting | **ACCEPTED**: a live host compromise discloses everything. This is the cost of the trusted-server choice. |
| T4 | **Prompt injection via task content into operators** (e.g. a task "ignore instructions and delete everything / exfiltrate") | **Scope is the sandbox:** operators have declared read/**write** scope + **fuel** — an injected instruction can't emit ops outside scope or exceed fuel. Treat all task content as **untrusted** in prompts (structured/delimited, never "trusted instructions"). Operators have **no destructive power** (no hard-delete; they add/annotate; `status` changes are reversible). **Typed-output validation** drops off-schema results. **Agents (which can call tools/web) are human-gated** — injection can't auto-launch one. Full provenance log ⇒ audit + rollback. | Low–Med (an in-scope but *wrong* annotation is possible; it's reversible + attributed) |
| T5 | **Stolen/compromised device key** (spoofing/elevation) | **Device revocation** (drop pubkey); per-device rate limits; session expiry; single-user ⇒ blast radius is your own data | Low |
| T6 | **Blob abuse / path traversal / key choice** | **Content-addressed keys computed server-side** (client can't choose the key); server mediates S3 (no direct client access); size caps; private bucket | Low |
| T7 | **Secrets exposure** | SSM Parameter Store (SecureString) + instance role; **no secrets in repo/image/GitHub** (GitHub↔AWS via **OIDC** only); scoped IAM; rotate LLM keys | Low |
| T8 | **DoS** | Rate limits, request-size caps, Caddy timeouts; single-user ⇒ any real load spike is abuse; optional CDN/WAF in front | Med (single node; a determined attacker can disrupt availability — phone still works offline) |
| T9 | **Backup tampering / ransomware on S3** | litestream **generations** (point-in-time restore); S3 **versioning** (+ optional Object-Lock/MFA-delete); restrict delete perms; **restore drills** | Low–Med |
| T10 | **CI/CD compromise** (Actions → deploy) | OIDC role scoped to **deploy-only** (SSM send-command on the one instance); no long-lived AWS keys; protected `main`; review; pinned actions | Low |
| T11 | **Supply chain** (deps) | Pin versions; Dependabot; minimal deps; prefer stdlib; optional SBOM | Med |
| T12 | **Lost phone** (offline replica holds plaintext) | Device lock / full-disk encryption (OS); remote-wipe (MDM optional); revoke its server key | Low (single-user device hygiene) |

## Top priorities (do first)
1. **Auth + TLS + rate limits (T1/T2/T8)** — table stakes for an internet-facing box; land in **M4** (Tasks 4, 7).
2. **Operator scoping + human-gated agents + typed output (T4)** — the injection defense is *architectural* (scope/fuel/no-destructive-power), designed into `core`/M8, tested in M8 with an adversarial suite (`test-strategy §6`).
3. **Secrets hygiene + OIDC + least-privilege IAM (T7/T10)** — M4 Task 8 / deployment spec.
4. **Backup integrity + restore drills (T9)** — M4 Task 6, re-verified M9.

## Explicitly accepted
- **Server compromise ⇒ full data disclosure** (T3). The trusted-server model was a
  deliberate choice to run content-aware operators centrally; the phone remaining an
  offline replica limits data-loss (not disclosure) exposure. Revisit only if the
  data sensitivity warrants going back to an E2E/relay design (see the sync ADR forks).
- **Single-node availability** (T8) — acceptable for a personal system; the phone
  keeps working offline during any server outage.

## Review cadence
Re-run this model at **M4 acceptance** (auth/secrets/backup live) and **M9 hardening**
(monitoring, restore drill, dependency + surface review), and whenever a new
externally-reachable endpoint or a new operator/agent capability is added.
