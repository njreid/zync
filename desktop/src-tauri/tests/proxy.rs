//! End-to-end tests of the local reverse proxy: HTTP forwarding with
//! server-injected bearer auth over pinned TLS, and WebSocket bridging.

mod fake_phone;

use desktop_lib::discovery::DiscoveredPhone;
use desktop_lib::identity::{DeviceIdentity, InMemoryStore};
use desktop_lib::pairing::pair;
use desktop_lib::paired_store::{
    forget, list_paired, load_paired, save_paired, InMemoryPairedStore,
};
use desktop_lib::proxy::start_proxy;
use fake_phone::spawn_fake_phone;
use futures_util::StreamExt;
use tokio_tungstenite::tungstenite::Message;

fn discovered_phone_for(fake: &fake_phone::FakePhone) -> DiscoveredPhone {
    DiscoveredPhone {
        name: "fake-phone".to_string(),
        host: fake.host,
        port: fake.port,
        fp_hint: None,
    }
}

async fn pair_with_fake(fake: &fake_phone::FakePhone) -> desktop_lib::pairing::PairedPhone {
    let phone = discovered_phone_for(fake);
    let id = DeviceIdentity::load_or_create_with("test-desktop", &InMemoryStore::default()).unwrap();
    pair(&id, &phone, |_| {}).await.expect("pairing should succeed")
}

#[tokio::test]
async fn proxy_injects_bearer_token_and_forwards_over_pinned_tls() {
    let fake = spawn_fake_phone().await;
    let paired = pair_with_fake(&fake).await;

    let handle = start_proxy(paired).await.expect("start_proxy");

    // Plain, non-pinned client, NO Authorization header supplied by the
    // caller — proves the proxy itself injects the bearer token.
    let client = reqwest::Client::new();
    let resp = client
        .get(format!("http://127.0.0.1:{}/api/roots", handle.port))
        .send()
        .await
        .expect("proxied request should succeed");

    assert_eq!(resp.status(), 200);
    let body = resp.text().await.unwrap();
    assert!(body.contains("Inbox"), "body: {body}");
    assert!(body.contains("Someday"), "body: {body}");

    let _ = handle.shutdown.send(());
}

#[tokio::test]
async fn proxy_forwards_caller_supplied_authorization_is_overwritten() {
    // A caller-supplied Authorization header must not leak through /
    // override the proxy's own injected token: send a bogus one and still
    // expect success (proves the proxy overwrites rather than forwards it).
    let fake = spawn_fake_phone().await;
    let paired = pair_with_fake(&fake).await;
    let handle = start_proxy(paired).await.expect("start_proxy");

    let client = reqwest::Client::new();
    let resp = client
        .get(format!("http://127.0.0.1:{}/api/roots", handle.port))
        .header("Authorization", "Bearer totally-bogus-token")
        .send()
        .await
        .expect("proxied request should succeed");

    assert_eq!(resp.status(), 200);
    let _ = handle.shutdown.send(());
}

#[tokio::test]
async fn proxy_bridges_websocket_events() {
    let fake = spawn_fake_phone().await;
    let paired = pair_with_fake(&fake).await;
    let handle = start_proxy(paired).await.expect("start_proxy");

    let url = format!("ws://127.0.0.1:{}/api/events", handle.port);
    let (mut ws, _resp) = tokio_tungstenite::connect_async(&url)
        .await
        .expect("ws connect should succeed");

    let first = ws.next().await.expect("should receive a frame").expect("frame ok");
    let text = match first {
        Message::Text(t) => t.to_string(),
        other => panic!("expected text frame, got {other:?}"),
    };
    assert!(text.contains("hello"), "frame: {text}");

    let _ = ws.close(None).await;
    let _ = handle.shutdown.send(());
}

#[tokio::test]
async fn pair_logic_persists_result_and_forwards_confirm_code() {
    // Exercises commands::pair_logic (not pairing::pair directly) to prove
    // the persistence + confirm-code-callback wiring that the thin
    // #[tauri::command] wrapper relies on, without needing a real
    // tauri::AppHandle.
    let fake = spawn_fake_phone().await;
    let phone = discovered_phone_for(&fake);
    let id = DeviceIdentity::load_or_create_with("unit-test", &InMemoryStore::default()).unwrap();
    let store = InMemoryPairedStore::default();
    let seen = std::sync::Mutex::new(None);

    let paired = desktop_lib::commands::pair_logic(&id, &phone, "unit-fake", &store, |code| {
        *seen.lock().unwrap() = Some(code.to_string());
    })
    .await
    .expect("pairing should succeed");

    assert!(seen.lock().unwrap().is_some());
    let loaded = load_paired(&store, "unit-fake").unwrap().expect("persisted");
    assert_eq!(loaded.session_token, paired.session_token);
}

#[tokio::test]
async fn paired_store_roundtrips_save_load_list_forget() {
    let store = InMemoryPairedStore::default();

    let fake = spawn_fake_phone().await;
    let paired = pair_with_fake(&fake).await;

    save_paired(&store, "my-phone", &paired).expect("save");

    let loaded = load_paired(&store, "my-phone")
        .expect("load ok")
        .expect("should be present");
    assert_eq!(loaded.host, paired.host);
    assert_eq!(loaded.tls_port, paired.tls_port);
    assert_eq!(loaded.fingerprint, paired.fingerprint);
    assert_eq!(loaded.session_token, paired.session_token);

    let names = list_paired(&store).expect("list");
    assert!(names.contains(&"my-phone".to_string()));

    forget(&store, "my-phone").expect("forget");
    assert!(load_paired(&store, "my-phone").expect("load ok").is_none());
    assert!(!list_paired(&store).expect("list").contains(&"my-phone".to_string()));
}
