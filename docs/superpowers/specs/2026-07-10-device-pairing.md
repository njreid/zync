# zync — Device Pairing (spec)

> **Status: 🟢 server side implemented (2026-07-10).** How a phone becomes an
> authorized replica of the central server. Supersedes the browser-auth open question
> in `2026-07-08-m4-server-foundation.md` §Task 4 for the *native* client. Browser/
> desktop thin clients (M7) use **WebAuthn/passkey** instead (slots into the existing
> `SessionStore.credentialCheck`); no passwords in the target state.

## Model
Mutual trust established out-of-band via a QR shown on the server terminal:
- **Server → phone:** the QR carries the server **address** + **Ed25519 public key**
  (pinned by the phone) + a **one-time code** + expiry.
- **Phone → server:** the phone generates its own Ed25519 device key and posts the
  public key + code to `/pair`. The server registers the key and returns a
  confirmation **signed by the server identity key**, which the phone verifies against
  the pinned public key (proves it's the genuine server; defense-in-depth beyond TLS,
  which survives Let's Encrypt cert rotation).

Thereafter the phone signs every request with its device key (M4 signed-request auth)
and reconnects to the EC2 server directly.

## Why the one-time code
The server rejects unknown device keys. `/pair` is unauthenticated (it's how you
*become* authenticated), so it must be gated: without the code, any client that
reached the box could self-register. The code is shown only in the operator's QR,
is single-use, and expires (~2 min) — so only someone who can see the server terminal
can pair a device.

## Flow
```
operator> server pair                # or: docker exec <ctr> /app/bin/server pair
   → loads/creates server identity key (ZYNC_SERVER_KEY_FILE)
   → PairingManager.open(): inserts a one-time code (DB, TTL 2m)
   → prints QR of  zync://pair?h=<addr>&k=<serverPubB64>&c=<code>&e=<expiry>

phone> scan QR → POST /pair { devicePublicKey, code }
server> PairingManager.redeem(code, key):
   code valid & unused & unexpired?  → register key (device table), sign confirmation
   → 200 { deviceId, serverPublicKey, confirmation }   (else 401)

phone> pin serverPublicKey; store deviceId + device key; sign future requests.
```

## Components (server)
- `pairing/ServerIdentity` — Ed25519 identity, persisted to `ZYNC_SERVER_KEY_FILE`.
- `pairing/PairingManager` — DB-backed one-time codes (`pairing_code` table);
  `open`/`redeem`; `deviceId = sha256(pubkey)[:16]`.
- `auth/SqlDeviceRegistry` — persistent allow-list (`device` table); survives restart.
- `pairing/PairingRoutes` — `POST /pair` (open, code-gated).
- `pairing/PairCommand` + `pairing/Qr` — the `server pair` CLI + terminal QR.
- The DB-backed code table lets the `zync pair` process and the running server (same
  SQLite file, distinct processes) share pairing state.

## Deferred / phone side (M5)
- Phone: scan QR, parse `zync://pair`, generate device key, `POST /pair`, verify the
  server confirmation, pin the server key, persist credentials.
- Revocation UI; multi-device management; rotating the server identity key.
- Browser/desktop **WebAuthn** (M7).
