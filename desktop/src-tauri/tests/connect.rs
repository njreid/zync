//! End-to-end test of `commands::connect_logic`: pairs against the fake
//! phone harness, persists the result, then proves `connect_logic` starts a
//! real proxy whose URL (as `proxy_url_logic` would report it) actually
//! forwards to the phone.

mod fake_phone;

use desktop_lib::commands::connect_logic;
use desktop_lib::discovery::DiscoveredPhone;
use desktop_lib::identity::{DeviceIdentity, InMemoryStore};
use desktop_lib::pairing::pair;
use desktop_lib::paired_store::{save_paired, InMemoryPairedStore};
use fake_phone::spawn_fake_phone;

fn discovered_phone_for(fake: &fake_phone::FakePhone) -> DiscoveredPhone {
    DiscoveredPhone {
        name: "fake-phone".to_string(),
        host: fake.host,
        port: fake.port,
        fp_hint: None,
    }
}

#[tokio::test]
async fn connect_logic_starts_proxy_and_returns_working_url() {
    let fake = spawn_fake_phone().await;
    let phone = discovered_phone_for(&fake);
    let id = DeviceIdentity::load_or_create_with("test-desktop", &InMemoryStore::default()).unwrap();

    let paired = pair(&id, &phone, |_| {}).await.expect("pairing should succeed");

    let store = InMemoryPairedStore::default();
    save_paired(&store, "my-phone", &paired).expect("save paired phone");

    let (handle, url) = connect_logic(&store, "my-phone")
        .await
        .expect("connect_logic should start the proxy");

    assert_eq!(url, format!("http://127.0.0.1:{}/", handle.port));

    // The URL connect_logic hands back is live: it actually proxies to the
    // paired phone (this is what the webview will `window.location =` to).
    let client = reqwest::Client::new();
    let resp = client
        .get(format!("{url}api/roots"))
        .send()
        .await
        .expect("request through the connected proxy should succeed");
    assert_eq!(resp.status(), 200);
    let body = resp.text().await.unwrap();
    assert!(body.contains("Inbox"), "body: {body}");

    let _ = handle.shutdown.send(());
}

#[tokio::test]
async fn connect_logic_fails_for_unknown_phone_name() {
    let store = InMemoryPairedStore::default();
    match connect_logic(&store, "nope").await {
        Ok(_) => panic!("connect_logic must fail for a phone that was never paired"),
        Err(e) => assert!(format!("{e:#}").contains("nope")),
    }
}
