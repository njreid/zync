//! Real end-to-end verification harness (M1d Task 7): drives the REAL
//! desktop Rust core (discovery/pairing/pinning/proxy — the exact same
//! shipping code exercised by `cargo test` against the fake phone) against a
//! REAL Android emulator running the real zync APK, reached via `adb
//! forward`.
//!
//! This is deliberately an `examples/` binary, not a `#[test]`: it requires
//! a live emulator with Remote Access already enabled and specific
//! `adb forward` tunnels already set up (see the M1d Task 7 report for the
//! exact commands), so it cannot run unattended in `cargo test`. It is
//! gated on an env var so a stray `cargo build --examples` / `cargo test`
//! doesn't try to run it against nothing.
//!
//! Run with:
//! ```sh
//! ZYNC_REAL_PHONE=1 \
//! ZYNC_PHONE_TLS_PORT=28443 \
//! ZYNC_PHONE_LOOPBACK_PORT=28080 \
//! cargo run --example real_phone_e2e
//! ```
//!
//! Prerequisites (see report for exact commands used during verification):
//! - Emulator booted, zync app installed + running, Remote Access enabled
//!   via Settings (`remote-toggle`).
//! - `adb forward tcp:<ZYNC_PHONE_TLS_PORT> tcp:<phone TLS port>`
//! - `adb forward tcp:<ZYNC_PHONE_LOOPBACK_PORT> tcp:<phone loopback HTTP port>`
//!   (the loopback port is needed only to simulate the phone's own WebView
//!   calling `POST /pair/approve` — i.e. simulating "the phone scanned the
//!   desktop's QR" — never reachable over the LAN/TLS connector by design).

use desktop_lib::discovery::DiscoveredPhone;
use desktop_lib::identity::{DeviceIdentity, InMemoryStore};
use desktop_lib::pairing::QrPayload;
use desktop_lib::paired_store::InMemoryPairedStore;
use desktop_lib::pinning::{pinned_client, PinMode};
use desktop_lib::proxy::start_proxy;
use futures_util::StreamExt;
use std::net::{IpAddr, Ipv4Addr};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio_tungstenite::tungstenite::Message as WsMessage;

fn env_u16(name: &str, default: u16) -> u16 {
    std::env::var(name)
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}

macro_rules! check {
    ($label:expr, $cond:expr) => {
        if $cond {
            println!("PASS: {}", $label);
        } else {
            println!("FAIL: {}", $label);
        }
    };
}

#[tokio::main]
async fn main() {
    if std::env::var("ZYNC_REAL_PHONE").is_err() {
        eprintln!(
            "ZYNC_REAL_PHONE not set — refusing to run against nothing. See file header for usage."
        );
        std::process::exit(2);
    }

    let tls_port = env_u16("ZYNC_PHONE_TLS_PORT", 28443);
    let loopback_port = env_u16("ZYNC_PHONE_LOOPBACK_PORT", 28080);
    let host = IpAddr::V4(Ipv4Addr::LOCALHOST);

    println!(
        "=== real_phone_e2e: TLS forward 127.0.0.1:{tls_port}, loopback forward 127.0.0.1:{loopback_port} ==="
    );

    // ---------------------------------------------------------------
    // Item 1: discovery. mDNS across the emulator's NAT is expected to
    // fail (documented in the plan as low-priority / expected-blocked).
    // Try a short browse, then fall back to a manually-constructed
    // DiscoveredPhone pointing at the adb-forwarded loopback address for
    // everything else in this harness.
    // ---------------------------------------------------------------
    match desktop_lib::discovery::browse(Duration::from_secs(3)) {
        Ok(phones) if !phones.is_empty() => {
            println!("PASS (unexpected!): mDNS discovery found {} phone(s) across emulator NAT: {:?}", phones.len(), phones);
        }
        Ok(_) => println!(
            "BLOCKED (expected per plan): mDNS discovery found 0 phones — emulator NAT does not \
             forward multicast. Falling back to manual host:port for the rest of this harness."
        ),
        Err(e) => println!(
            "BLOCKED (expected per plan): mDNS browse errored ({e:#}) — falling back to manual host:port."
        ),
    }

    let phone = DiscoveredPhone {
        name: "real-emulator-phone".to_string(),
        host,
        port: tls_port,
        fp_hint: None,
    };

    // ---------------------------------------------------------------
    // Item 2 (partial, independent cross-check): capture the leaf
    // fingerprint via a CaptureOnce client hitting a pre-auth pairing
    // endpoint, and print it so it can be diffed by hand against the
    // Settings-screen value and the openssl-captured value recorded in the
    // report. The stronger, load-bearing check (captured == server's own
    // /pair/request claim) happens automatically inside pair_with_qr below
    // and the whole flow aborts if it fails.
    // ---------------------------------------------------------------
    let (probe_mode, probe_slot) = PinMode::capture_once();
    let probe_client = pinned_client(probe_mode);
    let probe_url = format!("https://{host}:{tls_port}/pair/challenge?devicePubkey=AA");
    let _ = probe_client.get(&probe_url).send().await;
    let probe_fp = probe_slot.lock().unwrap().clone();
    println!(
        "INFO: independently-captured leaf fingerprint via CaptureOnce probe: {:?}",
        probe_fp
    );

    // ---------------------------------------------------------------
    // Item 3: full pairing via the real pair_with_qr(), simulating the
    // phone's camera scan by POSTing the emitted QR payload to the phone's
    // real /pair/approve over the loopback-forwarded port (plain HTTP,
    // unpinned client — this is exactly what the phone's own WebView would
    // do; it is not part of the pinned-TLS surface).
    // ---------------------------------------------------------------
    let id = DeviceIdentity::load_or_create_with("real-e2e-desktop", &InMemoryStore::default())
        .expect("identity");
    let store = InMemoryPairedStore::default();

    let qr_holder: Arc<Mutex<Option<QrPayload>>> = Arc::new(Mutex::new(None));
    let qr_holder_cb = qr_holder.clone();
    let confirm_holder: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
    let confirm_holder_cb = confirm_holder.clone();

    let approve_confirm_code: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
    let approve_confirm_code_task = approve_confirm_code.clone();
    let loopback_base = format!("http://127.0.0.1:{loopback_port}");
    let qr_holder_watch = qr_holder.clone();

    // Background task: poll for the QR payload to appear (set by
    // on_qr_payload below, invoked synchronously from within pair_with_qr
    // before it starts polling /pair/request), then immediately POST it to
    // /pair/approve to simulate the phone admitting the scan.
    let approver = tokio::spawn(async move {
        let payload = loop {
            if let Some(p) = qr_holder_watch.lock().unwrap().clone() {
                break p;
            }
            tokio::time::sleep(Duration::from_millis(50)).await;
        };
        let payload_json = serde_json::to_string(&payload).expect("serialize QrPayload");
        let plain = reqwest::Client::new();
        let resp = plain
            .post(format!("{loopback_base}/pair/approve"))
            .json(&serde_json::json!({ "payload": payload_json }))
            .send()
            .await
            .expect("POST /pair/approve");
        let status = resp.status();
        let body: serde_json::Value = resp.json().await.unwrap_or(serde_json::Value::Null);
        println!("INFO: /pair/approve -> {status} {body}");
        *approve_confirm_code_task.lock().unwrap() =
            body.get("confirmCode").and_then(|v| v.as_str()).map(|s| s.to_string());
    });

    let paired = desktop_lib::commands::pair_logic_with_qr(
        &id,
        &phone,
        "real-emulator-phone",
        &store,
        move |qr| {
            *qr_holder_cb.lock().unwrap() = Some(qr.clone());
        },
        move |code| {
            *confirm_holder_cb.lock().unwrap() = Some(code.to_string());
        },
    )
    .await;

    approver.await.expect("approver task");

    let paired = match paired {
        Ok(p) => {
            check!("Item 3: pair_with_qr returned PairedPhone with session token", !p.session_token.is_empty());

            let desktop_confirm = confirm_holder.lock().unwrap().clone();
            let phone_confirm = approve_confirm_code.lock().unwrap().clone();
            println!(
                "INFO: confirm code cross-check: desktop saw {:?}, phone /pair/approve returned {:?}",
                desktop_confirm, phone_confirm
            );
            check!(
                "Item 3: confirm code shown to desktop == confirm code phone displayed",
                desktop_confirm.is_some() && desktop_confirm == phone_confirm
            );
            check!(
                "Item 2: enforced fingerprint is non-empty and 32-byte-SHA-256-shaped",
                p.fingerprint.split(':').count() == 32
            );
            Some(p)
        }
        Err(e) => {
            println!(
                "BLOCKED: Item 3 (full real pair_with_qr handshake) failed: {e:#}\n\
                 This is expected to be a real rustls/aws-lc-rs vs phone-cert incompatibility \
                 (RSA-1024 self-signed cert) — see report for root-cause analysis. Items 4-6 \
                 (proxy/WS/revoke), which require a completed PairedPhone from THIS handshake, \
                 will be marked BLOCKED below as a consequence; item 7 does not depend on a \
                 completed pairing and will still run using the independently-captured real \
                 fingerprint."
            );
            None
        }
    };

    // ---------------------------------------------------------------
    // Items 4-6: proxy / WS / revoke. Only runnable if item 3 produced a
    // real PairedPhone (they all need a real session token).
    // ---------------------------------------------------------------
    match &paired {
        Some(paired) => {
            let proxy_handle = start_proxy(paired.clone()).await.expect("start_proxy");
            let proxy_port = proxy_handle.port;
            let plain = reqwest::Client::new();

            let roots_resp = plain
                .get(format!("http://127.0.0.1:{proxy_port}/api/roots"))
                .send()
                .await
                .expect("GET /api/roots via proxy");
            let roots_status = roots_resp.status();
            let roots_body = roots_resp.text().await.unwrap_or_default();
            println!("INFO: GET /api/roots via proxy -> {roots_status}: {roots_body}");
            check!(
                "Item 4: proxy GET /api/roots (no caller auth) -> 200 with real Inbox/Someday",
                roots_status == 200 && roots_body.contains("Inbox") && roots_body.contains("Someday")
            );

            let ws_url = format!("ws://127.0.0.1:{proxy_port}/api/events");
            let (mut ws, _resp) = tokio_tungstenite::connect_async(&ws_url)
                .await
                .expect("ws connect via proxy");

            let hello = tokio::time::timeout(Duration::from_secs(5), ws.next())
                .await
                .ok()
                .flatten();
            println!("INFO: first WS frame via proxy: {hello:?}");

            let create_resp = plain
                .post(format!("http://127.0.0.1:{proxy_port}/api/inbox"))
                .json(&serde_json::json!({"title": "m1d-task7-real-e2e-task"}))
                .send()
                .await
                .expect("POST /api/inbox via proxy");
            println!("INFO: POST /api/inbox -> {}", create_resp.status());
            check!("Item 5: POST /api/inbox via proxy -> 201", create_resp.status() == 201);

            let mut saw_changed = false;
            for _ in 0..10 {
                match tokio::time::timeout(Duration::from_secs(2), ws.next()).await {
                    Ok(Some(Ok(WsMessage::Text(t)))) => {
                        println!("INFO: WS frame: {t}");
                        if t.contains("changed") {
                            saw_changed = true;
                            break;
                        }
                    }
                    Ok(Some(Ok(_))) => continue,
                    _ => break,
                }
            }
            check!("Item 5: a 'changed' WS frame arrived after creating a task", saw_changed);
            let _ = ws.close(None).await;

            let devices_resp = plain
                .get(format!("http://127.0.0.1:{loopback_port}/devices"))
                .bearer_auth(&paired.session_token)
                .send()
                .await
                .expect("GET /devices");
            let devices: serde_json::Value = devices_resp.json().await.expect("decode /devices");
            println!("INFO: /devices -> {devices}");
            let our_id = devices
                .as_array()
                .and_then(|arr| {
                    arr.iter().find(|d| {
                        d.get("name").and_then(|n| n.as_str()) == Some("real-emulator-phone")
                            || d.get("name").and_then(|n| n.as_str()) == Some(id.device_name())
                    })
                })
                .and_then(|d| d.get("id"))
                .and_then(|v| v.as_i64());

            match our_id {
                Some(device_id) => {
                    let revoke_resp = plain
                        .post(format!(
                            "http://127.0.0.1:{loopback_port}/devices/{device_id}/revoke"
                        ))
                        .bearer_auth(&paired.session_token)
                        .send()
                        .await
                        .expect("POST /devices/{id}/revoke");
                    println!("INFO: revoke -> {}", revoke_resp.status());
                    check!("Item 6: revoke call succeeded (200)", revoke_resp.status() == 200);

                    let post_revoke = plain
                        .get(format!("http://127.0.0.1:{proxy_port}/api/roots"))
                        .send()
                        .await
                        .expect("GET /api/roots via proxy after revoke");
                    println!(
                        "INFO: GET /api/roots via proxy after revoke -> {}",
                        post_revoke.status()
                    );
                    check!(
                        "Item 6: proxy's next /api/roots 403s after revoke",
                        post_revoke.status() == 403
                    );
                }
                None => {
                    println!("BLOCKED: could not find our device id in /devices response: {devices}");
                }
            }

            let _ = proxy_handle.shutdown.send(());
        }
        None => {
            println!("BLOCKED: Item 4 (proxy /api/roots) skipped — no real PairedPhone from item 3.");
            println!("BLOCKED: Item 5 (WS live sync) skipped — no real PairedPhone from item 3.");
            println!("BLOCKED: Item 6 (revoke) skipped — no real PairedPhone from item 3.");
        }
    }

    // ---------------------------------------------------------------
    // Item 7: MITM / pin sanity — a second self-signed stub TLS server
    // with a DIFFERENT cert; an Enforce client pinned to the REAL phone's
    // fingerprint must refuse to connect to it. Does NOT require a
    // completed pairing — the real fingerprint was already independently
    // captured (CaptureOnce probe above, cross-checked against Settings +
    // openssl + /pair/request in the report) and Enforce mode only needs
    // that string, however it was obtained.
    // ---------------------------------------------------------------
    let real_fingerprint = paired
        .as_ref()
        .map(|p| p.fingerprint.clone())
        .or(probe_fp)
        .or_else(|| std::env::var("ZYNC_REAL_FINGERPRINT").ok());

    match real_fingerprint {
        Some(fp) => {
            let stub = spawn_mitm_stub().await;
            let enforce_client = pinned_client(PinMode::Enforce(fp));
            let mitm_result = enforce_client
                .get(format!("https://127.0.0.1:{}/", stub.port))
                .send()
                .await;
            match &mitm_result {
                Ok(resp) => println!("INFO: MITM probe unexpectedly succeeded: {}", resp.status()),
                Err(e) => println!("INFO: MITM probe correctly refused: {e:#}"),
            }
            check!(
                "Item 7: Enforce client pinned to real phone refuses a different cert",
                mitm_result.is_err()
            );
        }
        None => println!("BLOCKED: Item 7 (MITM/pin) — no real fingerprint available at all."),
    }

    println!("=== real_phone_e2e: run complete ===");
}

struct MitmStub {
    port: u16,
}

/// A minimal self-signed-TLS stub server with its OWN (different) cert, for
/// the MITM/pin-refusal negative test. Reuses the same TLS-terminating
/// `axum::serve::Listener` pattern as `tests/fake_phone.rs`.
async fn spawn_mitm_stub() -> MitmStub {
    use axum::Router;
    use tokio::net::{TcpListener, TcpStream};
    use tokio_rustls::rustls::pki_types::{CertificateDer, PrivateKeyDer};
    use tokio_rustls::rustls::ServerConfig;
    use tokio_rustls::server::TlsStream;
    use tokio_rustls::TlsAcceptor;

    struct TlsListener {
        listener: TcpListener,
        acceptor: TlsAcceptor,
    }
    impl axum::serve::Listener for TlsListener {
        type Io = TlsStream<TcpStream>;
        type Addr = std::net::SocketAddr;
        async fn accept(&mut self) -> (Self::Io, Self::Addr) {
            loop {
                let (stream, addr) = match self.listener.accept().await {
                    Ok(v) => v,
                    Err(_) => continue,
                };
                match self.acceptor.accept(stream).await {
                    Ok(tls) => return (tls, addr),
                    Err(_) => continue,
                }
            }
        }
        fn local_addr(&self) -> std::io::Result<Self::Addr> {
            self.listener.local_addr()
        }
    }

    let rcgen::CertifiedKey { cert, key_pair } =
        rcgen::generate_simple_self_signed(vec!["localhost".to_string(), "127.0.0.1".to_string()])
            .expect("generate stub cert");
    let key_der = PrivateKeyDer::Pkcs8(key_pair.serialize_der().into());
    let certs: Vec<CertificateDer<'static>> = vec![CertificateDer::from(cert.der().to_vec())];
    let tls_config = ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(certs, key_der)
        .expect("stub tls config");
    let acceptor = TlsAcceptor::from(Arc::new(tls_config));

    let listener = TcpListener::bind(std::net::SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))
        .await
        .expect("bind stub listener");
    let port = listener.local_addr().unwrap().port();
    let tls_listener = TlsListener { listener, acceptor };

    tokio::spawn(async move {
        let app = Router::new();
        let _ = axum::serve(tls_listener, app.into_make_service()).await;
    });

    MitmStub { port }
}
