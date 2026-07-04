//! Integration test: register a fake `_zync._tcp` service via mdns-sd's own
//! `ServiceDaemon` and verify `discovery::browse` finds it over loopback
//! mDNS. This exercises real discovery, not a stub.

use desktop_lib::discovery::browse;
use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;
use std::time::Duration;

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
