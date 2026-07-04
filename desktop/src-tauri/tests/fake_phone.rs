//! Reusable fake-phone test harness: an axum HTTPS server (rcgen
//! self-signed cert) that mimics the phone's pairing + `/api` routes, so the
//! whole desktop-side handshake and (later) reverse proxy are testable with
//! no real device.
//!
//! Reused by later tasks (e.g. the reverse-proxy test) — keep this module's
//! public surface small and stable: `spawn_fake_phone`, `FakePhoneConfig`,
//! `FakePhone`.

use axum::extract::{Query, State};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use base64::{engine::general_purpose::STANDARD, Engine as _};
use ed25519_dalek::{Signature, VerifyingKey};
use rand::RngExt;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use tokio::net::{TcpListener, TcpStream};
use tokio_rustls::rustls::pki_types::{CertificateDer, PrivateKeyDer};
use tokio_rustls::rustls::ServerConfig;
use tokio_rustls::server::TlsStream;
use tokio_rustls::TlsAcceptor;

/// SHA-256 of DER, uppercase colon-hex — same format used by the real
/// pinning verifier and the phone.
pub fn fingerprint_of(der: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(der);
    let digest = hasher.finalize();
    digest
        .iter()
        .map(|b| format!("{:02X}", b))
        .collect::<Vec<_>>()
        .join(":")
}

/// Confirm code formula, duplicated here (rather than depending on the
/// desktop crate's `DeviceIdentity`) so the harness can simulate a phone
/// that gets it WRONG for the negative test.
pub fn confirm_code_of(nonce: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(nonce.as_bytes());
    let digest = hasher.finalize();
    digest.iter().take(4).map(|b| format!("{:02x}", b)).collect::<String>().to_ascii_uppercase()
}

#[derive(Clone, Default)]
struct SharedState {
    inner: Arc<Mutex<Inner>>,
}

#[derive(Default)]
struct Inner {
    /// How many times /pair/request should answer "pending" before
    /// resolving.
    pending_count: u32,
    requests_seen: u32,
    /// The certFingerprint the fake will CLAIM in PairResultDto. Defaults to
    /// this server's own real fingerprint; tests can override it to a lie
    /// for the MITM cross-check negative test.
    claimed_fingerprint: Option<String>,
    /// The confirmCode the fake will report. Defaults to the correct
    /// formula; tests can override it to a wrong value.
    claimed_confirm_code: Option<String>,
    nonce: Option<String>,
    device_pubkey: Option<String>,
    issued_challenge: Option<String>,
    issued_token: Option<String>,
}

#[derive(Deserialize)]
struct PairRequestBody {
    #[serde(rename = "devicePubkey")]
    device_pubkey: String,
    nonce: String,
}

#[derive(Serialize)]
struct PairPendingDto {
    status: &'static str,
}

#[derive(Serialize)]
struct PairResultDto {
    #[serde(rename = "certFingerprint")]
    cert_fingerprint: String,
    #[serde(rename = "confirmCode")]
    confirm_code: String,
}

#[derive(Serialize)]
struct ChallengeDto {
    challenge: String,
}

#[derive(Deserialize)]
struct SessionRequestBody {
    #[serde(rename = "devicePubkey")]
    device_pubkey: String,
    challenge: String,
    signature: String,
}

#[derive(Serialize)]
struct SessionDto {
    token: String,
}

async fn pair_request(
    State(state): State<SharedState>,
    Json(body): Json<PairRequestBody>,
) -> Response {
    let mut inner = state.inner.lock().unwrap();
    inner.nonce = Some(body.nonce.clone());
    inner.device_pubkey = Some(body.device_pubkey.clone());

    if inner.requests_seen < inner.pending_count {
        inner.requests_seen += 1;
        return (StatusCode::ACCEPTED, Json(PairPendingDto { status: "pending" })).into_response();
    }

    let cert_fingerprint = inner.claimed_fingerprint.clone().unwrap_or_default();
    let confirm_code = inner
        .claimed_confirm_code
        .clone()
        .unwrap_or_else(|| confirm_code_of(&body.nonce));

    Json(PairResultDto {
        cert_fingerprint,
        confirm_code,
    })
    .into_response()
}

#[derive(Deserialize)]
struct ChallengeQuery {
    #[serde(rename = "devicePubkey")]
    #[allow(dead_code)]
    device_pubkey: String,
}

async fn pair_challenge(
    State(state): State<SharedState>,
    Query(_q): Query<ChallengeQuery>,
) -> Json<ChallengeDto> {
    let mut bytes = [0u8; 16];
    rand::rng().fill(&mut bytes[..]);
    let challenge = STANDARD.encode(bytes);
    state.inner.lock().unwrap().issued_challenge = Some(challenge.clone());
    Json(ChallengeDto { challenge })
}

async fn pair_session(
    State(state): State<SharedState>,
    Json(body): Json<SessionRequestBody>,
) -> Response {
    let expected_challenge = state.inner.lock().unwrap().issued_challenge.clone();
    let Some(expected_challenge) = expected_challenge else {
        return StatusCode::UNAUTHORIZED.into_response();
    };
    if expected_challenge != body.challenge {
        return StatusCode::UNAUTHORIZED.into_response();
    }

    let pubkey_bytes = match STANDARD.decode(&body.device_pubkey) {
        Ok(b) if b.len() == 32 => b,
        _ => return StatusCode::UNAUTHORIZED.into_response(),
    };
    let verifying_key = match VerifyingKey::from_bytes(&pubkey_bytes.try_into().unwrap()) {
        Ok(k) => k,
        Err(_) => return StatusCode::UNAUTHORIZED.into_response(),
    };
    let sig_bytes = match STANDARD.decode(&body.signature) {
        Ok(b) => b,
        Err(_) => return StatusCode::UNAUTHORIZED.into_response(),
    };
    let signature = match Signature::from_slice(&sig_bytes) {
        Ok(s) => s,
        Err(_) => return StatusCode::UNAUTHORIZED.into_response(),
    };

    if verifying_key
        .verify_strict(expected_challenge.as_bytes(), &signature)
        .is_err()
    {
        return StatusCode::UNAUTHORIZED.into_response();
    }

    let mut token_bytes = [0u8; 16];
    rand::rng().fill(&mut token_bytes[..]);
    let token = STANDARD.encode(token_bytes);
    state.inner.lock().unwrap().issued_token = Some(token.clone());

    Json(SessionDto { token }).into_response()
}

async fn api_roots(State(state): State<SharedState>, headers: HeaderMap) -> Response {
    let expected = state.inner.lock().unwrap().issued_token.clone();
    let auth = headers
        .get("authorization")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let ok = match expected {
        Some(token) => auth == format!("Bearer {token}"),
        None => false,
    };
    if !ok {
        return StatusCode::FORBIDDEN.into_response();
    }
    Json(serde_json::json!([{"title": "Inbox"}, {"title": "Someday"}])).into_response()
}

/// Handle to a running fake phone. Dropping this does not stop the server;
/// hold onto it for the lifetime of the test (the underlying task is
/// detached but bound to an ephemeral port that's freed when the process
/// exits or the test binary tears down).
pub struct FakePhone {
    pub host: IpAddr,
    pub port: u16,
    /// The fake's REAL leaf certificate fingerprint (what a correct pinning
    /// verifier will capture from the actual TLS handshake).
    pub real_fingerprint: String,
}

/// Configuration knobs for `spawn_fake_phone_with`, letting tests simulate
/// a misbehaving/MITM phone.
#[derive(Default)]
pub struct FakePhoneConfig {
    /// Number of times `/pair/request` answers 202-pending before resolving.
    pub pending_count: u32,
    /// Override the `certFingerprint` claimed in `PairResultDto`. Defaults
    /// to the fake's real fingerprint (honest behavior).
    pub claimed_fingerprint: Option<String>,
    /// Override the `confirmCode` claimed in `PairResultDto`. Defaults to
    /// the correct SHA-256-derived value.
    pub claimed_confirm_code: Option<String>,
}

/// Spawn a fake phone with default (honest) behavior: resolves pairing
/// immediately with correct fingerprint + confirm code.
pub async fn spawn_fake_phone() -> FakePhone {
    spawn_fake_phone_with(FakePhoneConfig::default()).await
}

/// Spawn a fake phone with the given config, for negative-path tests.
pub async fn spawn_fake_phone_with(config: FakePhoneConfig) -> FakePhone {
    let rcgen::CertifiedKey { cert, key_pair } =
        rcgen::generate_simple_self_signed(vec!["localhost".to_string(), "127.0.0.1".to_string()])
            .expect("generate self-signed cert");
    let cert_der = cert.der().to_vec();
    let real_fingerprint = fingerprint_of(&cert_der);

    let state = SharedState::default();
    {
        let mut inner = state.inner.lock().unwrap();
        inner.pending_count = config.pending_count;
        inner.claimed_fingerprint = Some(
            config
                .claimed_fingerprint
                .unwrap_or_else(|| real_fingerprint.clone()),
        );
        inner.claimed_confirm_code = config.claimed_confirm_code;
    }

    let app = Router::new()
        .route("/pair/request", post(pair_request))
        .route("/pair/challenge", get(pair_challenge))
        .route("/pair/session", post(pair_session))
        .route("/api/roots", get(api_roots))
        .with_state(state);

    let key_der = PrivateKeyDer::Pkcs8(key_pair.serialize_der().into());
    let certs: Vec<CertificateDer<'static>> = vec![CertificateDer::from(cert_der.clone())];

    let tls_config = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certs, key_der)
        .expect("build server tls config");
    let acceptor = TlsAcceptor::from(Arc::new(tls_config));

    let listener = TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))
        .await
        .expect("bind fake phone listener");
    let port = listener.local_addr().unwrap().port();

    let tls_listener = TlsListener { listener, acceptor };

    tokio::spawn(async move {
        let _ = axum::serve(tls_listener, app.into_make_service()).await;
    });

    FakePhone {
        host: IpAddr::V4(Ipv4Addr::LOCALHOST),
        port,
        real_fingerprint,
    }
}

/// An `axum::serve::Listener` that terminates TLS on every accepted TCP
/// connection before handing it to axum, so the whole `Router` can be
/// served with a single `axum::serve` call instead of a hand-rolled accept
/// loop per connection.
struct TlsListener {
    listener: TcpListener,
    acceptor: TlsAcceptor,
}

impl axum::serve::Listener for TlsListener {
    type Io = TlsStream<TcpStream>;
    type Addr = SocketAddr;

    async fn accept(&mut self) -> (Self::Io, Self::Addr) {
        loop {
            let (stream, addr) = match self.listener.accept().await {
                Ok(v) => v,
                Err(_) => continue,
            };
            match self.acceptor.accept(stream).await {
                Ok(tls_stream) => return (tls_stream, addr),
                Err(_) => continue, // bad handshake; wait for the next connection
            }
        }
    }

    fn local_addr(&self) -> std::io::Result<Self::Addr> {
        self.listener.local_addr()
    }
}
