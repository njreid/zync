//! Integration test: register a fake `_zync._tcp` service via mdns-sd's own
//! `ServiceDaemon` and verify `discovery::browse` finds it over loopback
//! mDNS. This exercises real discovery, not a stub.

use desktop_lib::discovery::{browse, subscribe, DiscoveryEvent};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;
use std::time::{Duration, Instant};

/// mDNS over loopback can occasionally miss the first announcement on a
/// loaded CI box, so we retry the browse a couple of times before failing.
#[test]
fn discovers_registered_zync_service() {
    let daemon = ServiceDaemon::new().expect("failed to create mdns daemon");

    let service_type = "_zync._tcp.local.";
    let instance_name = format!(
        "zync-test-phone-{}",
        std::process::id() // avoid collisions across repeated test runs
    );
    let host_name = format!("{}.local.", instance_name);
    let port = 54321;

    let mut properties = HashMap::new();
    properties.insert("fp".to_string(), "AA:BB".to_string());

    let service_info = ServiceInfo::new(
        service_type,
        &instance_name,
        &host_name,
        "127.0.0.1",
        port,
        Some(properties),
    )
    .expect("failed to build ServiceInfo")
    .enable_addr_auto();

    daemon
        .register(service_info)
        .expect("failed to register fake phone service");

    let mut found = Vec::new();
    for attempt in 0..3 {
        let timeout = Duration::from_secs(2 + attempt);
        let phones = browse(timeout).expect("browse failed");
        if let Some(phone) = phones.iter().find(|p| p.name == instance_name) {
            found.push(phone.clone());
            break;
        }
    }

    let phone = found
        .into_iter()
        .next()
        .expect("registered fake phone was not discovered via mDNS");

    assert_eq!(phone.name, instance_name);
    assert_eq!(phone.port, port);
    assert_eq!(phone.fp_hint.as_deref(), Some("AA:BB"));

    let _ = daemon.shutdown();
}

/// Exercises the live `subscribe()` streaming variant end-to-end over
/// loopback mDNS: register a service AFTER subscribing and assert an
/// `Added` event arrives, then unregister it and assert a `Removed` event
/// arrives — proving the background daemon thread translates both edges.
#[test]
fn subscribe_streams_added_then_removed_events() {
    let rx = subscribe().expect("subscribe should start a live browse");

    let daemon = ServiceDaemon::new().expect("failed to create mdns daemon");
    let service_type = "_zync._tcp.local.";
    let instance_name = format!("zync-sub-phone-{}", std::process::id());
    let host_name = format!("{}.local.", instance_name);
    let port = 54322;

    let mut properties = HashMap::new();
    properties.insert("fp".to_string(), "CC:DD".to_string());

    let service_info = ServiceInfo::new(
        service_type,
        &instance_name,
        &host_name,
        "127.0.0.1",
        port,
        Some(properties),
    )
    .expect("failed to build ServiceInfo")
    .enable_addr_auto();

    daemon
        .register(service_info)
        .expect("failed to register fake phone service");

    // Wait (bounded) for an Added event naming our instance. Other zync
    // services on the LAN could interleave, so filter by name.
    let added = wait_for_event(&rx, Duration::from_secs(6), |ev| match ev {
        DiscoveryEvent::Added(p) if p.name == instance_name => Some(p.clone()),
        _ => None,
    })
    .expect("subscribe should stream an Added event for the registered service");
    assert_eq!(added.port, port);
    assert_eq!(added.fp_hint.as_deref(), Some("CC:DD"));

    daemon
        .unregister(&format!("{instance_name}.{service_type}"))
        .expect("failed to unregister fake phone service");

    let removed = wait_for_event(&rx, Duration::from_secs(6), |ev| match ev {
        DiscoveryEvent::Removed(name) if *name == instance_name => Some(name.clone()),
        _ => None,
    })
    .expect("subscribe should stream a Removed event after unregister");
    assert_eq!(removed, instance_name);

    let _ = daemon.shutdown();
    // Dropping `rx` here signals the subscription's background thread to stop.
}

/// Block up to `timeout` for a `DiscoveryEvent` the `pick` closure accepts,
/// draining (and discarding) any non-matching events in the meantime.
fn wait_for_event<T>(
    rx: &std::sync::mpsc::Receiver<DiscoveryEvent>,
    timeout: Duration,
    pick: impl Fn(&DiscoveryEvent) -> Option<T>,
) -> Option<T> {
    let deadline = Instant::now() + timeout;
    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return None;
        }
        match rx.recv_timeout(remaining) {
            Ok(ev) => {
                if let Some(v) = pick(&ev) {
                    return Some(v);
                }
            }
            Err(_) => return None,
        }
    }
}
