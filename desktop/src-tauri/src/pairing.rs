//! Ed25519 pairing handshake driver: drives the desktop side of the phone's
//! §8b pairing flow over a pinned-TLS connection.
//!
//! Flow (see the phone-side contract in
//! `docs/superpowers/plans/2026-07-04-m1d-tauri-desktop.md`):
//! 1. Generate a random nonce, emit the QR payload for the UI to render.
//! 2. Poll `POST /pair/request` with a trust-on-first-use (`CaptureOnce`)
//!    pinned client until the phone user approves the scan.
//! 3. Cross-check the captured leaf fingerprint against the server's own
//!    claim (`certFingerprint`) AND a locally-computable `confirmCode`
//!    derived from the nonce. Either mismatch aborts.
//! 4. Switch to an `Enforce`d pinned client and complete the challenge/
//!    signature exchange to get a session token.
//!
//! Threat model — what the confirm code does and does NOT protect against:
//! These two checks catch a passive relay, a stale/cached endpoint, or a
//! misdirected connection, and they correlate this pairing attempt with the
//! specific QR the human scanned. They do NOT stop an *active* LAN relay
//! within the pairing window. Such a relay terminates the desktop's TLS
//! itself, so it reads the plaintext nonce out of the `/pair/request` body,
//! recomputes the identical nonce-derived `confirmCode`, and reports its OWN
//! leaf fingerprint as `certFingerprint` — which is exactly the fingerprint
//! we captured from it. So both automated checks pass and the human-visible
//! confirm code is byte-identical on both screens. The only thing defending
//! against an active relay TODAY is the out-of-band physical QR scan. The
//! intended M2 hardening is to bind the nonce into the challenge signature
//! (so a relay that can't forge the phone's Ed25519 signature over *this*
//! nonce is caught) and/or to have the human compare the captured leaf
//! fingerprint out-of-band.

use crate::discovery::DiscoveredPhone;
use crate::identity::DeviceIdentity;
use crate::pinning::{pinned_client, PinMode};
use anyhow::{anyhow, bail, Context, Result};
use rand::RngExt;
use serde::{Deserialize, Serialize};
use std::net::IpAddr;
use std::time::Duration;

/// The QR payload the desktop displays for the phone to scan.
#[derive(Debug, Clone, Serialize)]
pub struct QrPayload {
    #[serde(rename = "devicePubkey")]
    pub device_pubkey: String,
    #[serde(rename = "deviceName")]
    pub device_name: String,
    pub nonce: String,
}

#[derive(Debug, Serialize)]
struct PairRequestBody {
    #[serde(rename = "devicePubkey")]
    device_pubkey: String,
    nonce: String,
}

#[derive(Debug, Deserialize)]
#[serde(untagged)]
enum PairRequestResponse {
    Result(PairResultDto),
    Pending(PairPendingDto),
}

#[derive(Debug, Deserialize)]
struct PairPendingDto {
    #[allow(dead_code)]
    status: String,
}

#[derive(Debug, Deserialize)]
struct PairResultDto {
    #[serde(rename = "certFingerprint")]
    cert_fingerprint: String,
    #[serde(rename = "confirmCode")]
    confirm_code: String,
}

#[derive(Debug, Deserialize)]
struct ChallengeDto {
    challenge: String,
}

#[derive(Debug, Serialize)]
struct SessionRequestBody {
    #[serde(rename = "devicePubkey")]
    device_pubkey: String,
    challenge: String,
    signature: String,
}

#[derive(Debug, Deserialize)]
struct SessionDto {
    token: String,
}

/// The outcome of a successful pairing: enough to build an `Enforce`-pinned
/// client and talk to the phone's `/api/**`.
#[derive(Debug, Clone)]
pub struct PairedPhone {
    pub host: IpAddr,
    pub tls_port: u16,
    pub fingerprint: String,
    pub session_token: String,
}

/// Total time budget for the "waiting for the phone user to approve the
/// scan" poll loop.
const PAIR_REQUEST_BUDGET: Duration = Duration::from_secs(120);
/// Backoff between poll attempts while `/pair/request` reports pending.
const PAIR_REQUEST_POLL_INTERVAL: Duration = Duration::from_millis(500);

fn base_url(host: IpAddr, port: u16) -> String {
    match host {
        IpAddr::V6(v6) => format!("https://[{v6}]:{port}"),
        IpAddr::V4(_) => format!("https://{host}:{port}"),
    }
}

/// Generate a URL-safe, >=128-bit random nonce.
fn generate_nonce() -> String {
    use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
    let mut bytes = [0u8; 18]; // 144 bits
    rand::rng().fill(&mut bytes[..]);
    URL_SAFE_NO_PAD.encode(bytes)
}

/// Drive the full pairing handshake against `phone`, using `id` as this
/// device's Ed25519 identity. `on_confirm_code` is invoked with the
/// server-reported confirm code once BOTH cross-checks (fingerprint +
/// confirm code) have already succeeded, so the human can additionally
/// eyeball it against the phone's display.
pub async fn pair(
    id: &DeviceIdentity,
    phone: &DiscoveredPhone,
    on_confirm_code: impl Fn(&str),
) -> Result<PairedPhone> {
    pair_with_qr(id, phone, |_qr| {}, on_confirm_code).await
}

/// Same handshake as [`pair`], additionally invoking `on_qr_payload` with the
/// QR payload as soon as it's constructed (before the `/pair/request` poll
/// loop starts), so a UI layer can render/display it for the phone to scan.
/// `pair` is a thin wrapper around this with a no-op `on_qr_payload` — the
/// two share every other step of the handshake byte-for-byte.
pub async fn pair_with_qr(
    id: &DeviceIdentity,
    phone: &DiscoveredPhone,
    on_qr_payload: impl Fn(&QrPayload),
    on_confirm_code: impl Fn(&str),
) -> Result<PairedPhone> {
    let nonce = generate_nonce();
    let device_pubkey = id.public_key_b64();

    // Step 2: emit the QR payload for the UI to render (the same nonce is
    // used below for /pair/request, so the phone can correlate the scan with
    // the incoming request).
    let qr_payload = QrPayload {
        device_pubkey: device_pubkey.clone(),
        device_name: id.device_name().to_string(),
        nonce: nonce.clone(),
    };
    on_qr_payload(&qr_payload);

    let base = base_url(phone.host, phone.port);

    // Step 3: poll /pair/request with a CaptureOnce pinned client.
    let (capture_mode, captured_fp) = PinMode::capture_once();
    let capture_client = pinned_client(capture_mode);

    let result = poll_pair_request(&capture_client, &base, &device_pubkey, &nonce).await?;

    let captured = captured_fp
        .lock()
        .unwrap()
        .clone()
        .ok_or_else(|| anyhow!("pairing: no certificate was captured during /pair/request"))?;

    // Step 4: verify BOTH independent signals before trusting anything.
    if captured != result.cert_fingerprint {
        bail!(
            "pairing aborted: captured TLS leaf fingerprint ({}) does not match \
             the server's claimed certFingerprint ({}) — possible MITM",
            captured,
            result.cert_fingerprint
        );
    }
    let expected_confirm_code = DeviceIdentity::confirm_code(&nonce);
    if expected_confirm_code != result.confirm_code {
        bail!(
            "pairing aborted: confirm code mismatch (expected {}, server said {}) — \
             possible MITM",
            expected_confirm_code,
            result.confirm_code
        );
    }

    on_confirm_code(&result.confirm_code);

    // Step 5: switch to Enforce and complete challenge/session.
    let fingerprint = captured;
    let enforce_client = pinned_client(PinMode::Enforce(fingerprint.clone()));

    let challenge_url = format!(
        "{base}/pair/challenge?devicePubkey={}",
        urlencode(&device_pubkey)
    );
    let challenge: ChallengeDto = enforce_client
        .get(&challenge_url)
        .send()
        .await
        .context("GET /pair/challenge")?
        .error_for_status()
        .context("/pair/challenge returned an error status")?
        .json()
        .await
        .context("decode ChallengeDto")?;

    let signature = id.sign_challenge_b64(&challenge.challenge);

    let session: SessionDto = enforce_client
        .post(format!("{base}/pair/session"))
        .json(&SessionRequestBody {
            device_pubkey: device_pubkey.clone(),
            challenge: challenge.challenge,
            signature,
        })
        .send()
        .await
        .context("POST /pair/session")?
        .error_for_status()
        .context("/pair/session returned an error status")?
        .json()
        .await
        .context("decode SessionDto")?;

    Ok(PairedPhone {
        host: phone.host,
        tls_port: phone.port,
        fingerprint,
        session_token: session.token,
    })
}

async fn poll_pair_request(
    client: &reqwest::Client,
    base: &str,
    device_pubkey: &str,
    nonce: &str,
) -> Result<PairResultDto> {
    let deadline = tokio::time::Instant::now() + PAIR_REQUEST_BUDGET;
    loop {
        let response = client
            .post(format!("{base}/pair/request"))
            .json(&PairRequestBody {
                device_pubkey: device_pubkey.to_string(),
                nonce: nonce.to_string(),
            })
            .send()
            .await
            .context("POST /pair/request")?;

        if !response.status().is_success() {
            bail!("/pair/request returned {}", response.status());
        }

        let body: PairRequestResponse = response.json().await.context("decode /pair/request response")?;
        match body {
            PairRequestResponse::Result(result) => return Ok(result),
            PairRequestResponse::Pending(_) => {
                if tokio::time::Instant::now() >= deadline {
                    bail!("pairing timed out waiting for phone approval");
                }
                tokio::time::sleep(PAIR_REQUEST_POLL_INTERVAL).await;
            }
        }
    }
}

fn urlencode(s: &str) -> String {
    // devicePubkey is standard base64: contains only [A-Za-z0-9+/=], of
    // which '+', '/', '=' need percent-encoding in a query string.
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'+' => out.push_str("%2B"),
            b'/' => out.push_str("%2F"),
            b'=' => out.push_str("%3D"),
            _ => out.push(b as char),
        }
    }
    out
}
