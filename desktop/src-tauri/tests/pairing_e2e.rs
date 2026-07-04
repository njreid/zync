//! End-to-end tests of the pairing handshake against the fake-phone
//! harness: happy path plus the three MITM-shaped negative paths called out
//! in the task brief.

mod fake_phone;

use desktop_lib::discovery::DiscoveredPhone;
use desktop_lib::identity::{DeviceIdentity, InMemoryStore};
use desktop_lib::pairing::pair;
use desktop_lib::pinning::{pinned_client, PinMode};
use fake_phone::{spawn_fake_phone, spawn_fake_phone_with, FakePhoneConfig};

fn discovered_phone_for(fake: &fake_phone::FakePhone) -> DiscoveredPhone {
    DiscoveredPhone {
        name: "fake-phone".to_string(),
        host: fake.host,
        port: fake.port,
        fp_hint: None,
    }
}

#[tokio::test]
async fn happy_path_pairs_and_calls_api_roots() {
    let fake = spawn_fake_phone().await;
    let phone = discovered_phone_for(&fake);
    let id = DeviceIdentity::load_or_create_with("test-desktop", &InMemoryStore::default()).unwrap();

    let seen_confirm_code = std::sync::Mutex::new(None);
    let paired = pair(&id, &phone, |code| {
        *seen_confirm_code.lock().unwrap() = Some(code.to_string());
    })
    .await
    .expect("pairing should succeed against an honest fake phone");

    assert_eq!(paired.fingerprint, fake.real_fingerprint);
    assert!(seen_confirm_code.lock().unwrap().is_some());
    assert!(!paired.session_token.is_empty());

    let client = pinned_client(PinMode::Enforce(paired.fingerprint.clone()));
    let url = format!("https://{}:{}/api/roots", paired.host, paired.tls_port);
    let resp = client
        .get(&url)
        .bearer_auth(&paired.session_token)
        .send()
        .await
        .expect("request to /api/roots should succeed");

    assert_eq!(resp.status(), 200);
    let body: serde_json::Value = resp.json().await.unwrap();
    let titles: Vec<&str> = body
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v["title"].as_str().unwrap())
        .collect();
    assert_eq!(titles, vec!["Inbox", "Someday"]);
}

#[tokio::test]
async fn pairing_fails_when_claimed_fingerprint_does_not_match_captured_cert() {
    // A MITM (or a buggy/lying phone) claims a certFingerprint that doesn't
    // match the cert it actually terminated TLS with.
    let fake = spawn_fake_phone_with(FakePhoneConfig {
        claimed_fingerprint: Some(
            "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF"
                .to_string(),
        ),
        ..Default::default()
    })
    .await;
    let phone = discovered_phone_for(&fake);
    let id = DeviceIdentity::load_or_create_with("test-desktop", &InMemoryStore::default()).unwrap();

    let err = pair(&id, &phone, |_| {})
        .await
        .expect_err("pairing must abort on fingerprint cross-check mismatch");
    assert!(
        format!("{err:#}").to_lowercase().contains("fingerprint"),
        "error should mention the fingerprint mismatch: {err:#}"
    );
}

#[tokio::test]
async fn pairing_fails_when_confirm_code_is_wrong() {
    let fake = spawn_fake_phone_with(FakePhoneConfig {
        claimed_confirm_code: Some("DEADBEEF".to_string()),
        ..Default::default()
    })
    .await;
    let phone = discovered_phone_for(&fake);
    let id = DeviceIdentity::load_or_create_with("test-desktop", &InMemoryStore::default()).unwrap();

    let err = pair(&id, &phone, |_| {})
        .await
        .expect_err("pairing must abort on confirm code mismatch");
    assert!(
        format!("{err:#}").to_lowercase().contains("confirm code"),
        "error should mention the confirm code mismatch: {err:#}"
    );
}

#[tokio::test]
async fn enforce_client_rejects_a_different_cert_after_pairing() {
    let fake = spawn_fake_phone().await;
    let phone = discovered_phone_for(&fake);
    let id = DeviceIdentity::load_or_create_with("test-desktop", &InMemoryStore::default()).unwrap();

    let paired = pair(&id, &phone, |_| {}).await.expect("pairing should succeed");

    // A second, distinct fake phone (different self-signed cert). Even
    // though it speaks the exact same protocol, the pin from the FIRST
    // phone must not accept it.
    let other_fake = spawn_fake_phone().await;
    assert_ne!(paired.fingerprint, other_fake.real_fingerprint);

    let client = pinned_client(PinMode::Enforce(paired.fingerprint.clone()));
    let url = format!("https://{}:{}/api/roots", other_fake.host, other_fake.port);
    let result = client.get(&url).send().await;

    assert!(
        result.is_err(),
        "an Enforce client pinned to the first phone's cert must reject the second phone's cert"
    );
}
