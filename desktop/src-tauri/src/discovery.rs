//! mDNS discovery of zync phones advertising `_zync._tcp` on the LAN.
//!
//! Phone-side contract (frozen, M1c): the Android app registers
//! `_zync._tcp` via `NsdManager` with a TXT attribute `fp` = short
//! fingerprint hint, on its LAN TLS port. This module browses for that
//! service type via `mdns-sd`, resolves each instance to a host/port, and
//! reads the `fp` TXT attribute.

use anyhow::Result;
use mdns_sd::{ResolvedService, ServiceDaemon, ServiceEvent};
use serde::Serialize;
use std::collections::HashMap;
use std::net::IpAddr;
use std::sync::mpsc::Receiver;
use std::time::{Duration, Instant};

/// The DNS-SD service type the phone advertises.
pub const SERVICE_TYPE: &str = "_zync._tcp.local.";

/// A phone discovered on the LAN via mDNS.
#[derive(Clone, Debug, Serialize)]
pub struct DiscoveredPhone {
    pub name: String,
    pub host: IpAddr,
    pub port: u16,
    pub fp_hint: Option<String>,
}

/// A live discovery update, for UI consumers that want to react to
/// phones appearing/disappearing rather than polling `browse`.
#[derive(Clone, Debug)]
pub enum DiscoveryEvent {
    Added(DiscoveredPhone),
    Removed(String),
}

/// Browse `_zync._tcp.local.` for up to `timeout`, returning every phone
/// resolved during that window, de-duplicated by instance name (last
/// resolution wins).
///
/// Keeps the `ServiceDaemon` alive for the whole browse window — dropping
/// it early stops discovery — and drains its event receiver until either
/// the timeout elapses or the receiver disconnects.
pub fn browse(timeout: Duration) -> Result<Vec<DiscoveredPhone>> {
    let daemon = ServiceDaemon::new()?;
    let receiver = daemon.browse(SERVICE_TYPE)?;

    let mut phones: HashMap<String, DiscoveredPhone> = HashMap::new();
    let deadline = Instant::now() + timeout;

    loop {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            break;
        }
        match receiver.recv_timeout(remaining) {
            Ok(ServiceEvent::ServiceResolved(resolved)) => {
                let phone = to_discovered_phone(&resolved);
                phones.insert(phone.name.clone(), phone);
            }
            Ok(_) => continue,
            Err(_) => break, // timed out or the daemon's channel disconnected
        }
    }

    // Best-effort: stop browsing and shut the daemon down before returning.
    let _ = daemon.stop_browse(SERVICE_TYPE);
    let _ = daemon.shutdown();

    Ok(phones.into_values().collect())
}

/// Start a live subscription to `_zync._tcp.local.` announcements.
///
/// This is a minimal streaming variant: it spawns a background thread that
/// owns the `ServiceDaemon` for as long as the returned `Receiver` (or its
/// sender) is alive, translating `ServiceResolved`/`ServiceRemoved` events
/// into `DiscoveryEvent`s. The UI can either block-read from this channel
/// on a dedicated thread or poll it with `try_recv`. It does not attempt
/// re-connection or de-duplication beyond what mdns-sd itself provides —
/// full de-duping (as `browse` does) is left to the caller if needed, since
/// the primary consumer here is a live "phones appearing" list.
pub fn subscribe() -> Result<Receiver<DiscoveryEvent>> {
    let daemon = ServiceDaemon::new()?;
    let mdns_receiver = daemon.browse(SERVICE_TYPE)?;
    let (tx, rx) = std::sync::mpsc::channel();

    std::thread::spawn(move || {
        // The daemon must stay alive for the subscription to keep working;
        // it's moved into this thread and dropped only when the loop ends
        // (i.e. when `tx` can no longer send, meaning the caller dropped `rx`).
        let _daemon = daemon;
        for event in mdns_receiver.iter() {
            let mapped = match event {
                ServiceEvent::ServiceResolved(resolved) => {
                    Some(DiscoveryEvent::Added(to_discovered_phone(&resolved)))
                }
                ServiceEvent::ServiceRemoved(_ty, fullname) => {
                    Some(DiscoveryEvent::Removed(instance_name(&fullname, SERVICE_TYPE)))
                }
                _ => None,
            };
            if let Some(event) = mapped {
                if tx.send(event).is_err() {
                    break; // receiver dropped; stop the subscription
                }
            }
        }
    });

    Ok(rx)
}

fn to_discovered_phone(resolved: &ResolvedService) -> DiscoveredPhone {
    let name = instance_name(&resolved.fullname, &resolved.ty_domain);
    // Prefer IPv4 if present; otherwise fall back to whatever we have
    // (e.g. IPv6-only networks). mdns-sd returns a HashSet so order isn't
    // guaranteed, hence the explicit search for a V4 address first.
    let host = resolved
        .addresses
        .iter()
        .map(|scoped| scoped.to_ip_addr())
        .find(|ip| ip.is_ipv4())
        .or_else(|| resolved.addresses.iter().next().map(|s| s.to_ip_addr()))
        .unwrap_or_else(|| {
            // A resolved service with no addresses at all is unusual (the
            // phone should always advertise at least one). Surface it rather
            // than silently handing the UI an unconnectable 0.0.0.0.
            log::warn!(
                "discovered zync service {:?} advertised no resolvable address; \
                 using 0.0.0.0 placeholder (it will not be connectable)",
                resolved.fullname
            );
            IpAddr::from([0, 0, 0, 0])
        });

    let fp_hint = resolved
        .txt_properties
        .get("fp")
        .map(|prop| prop.val_str().to_string());

    DiscoveredPhone {
        name,
        host,
        port: resolved.port,
        fp_hint,
    }
}

/// Strip the `.{ty_domain}` suffix from a fullname to recover the instance
/// name, e.g. `"my-phone._zync._tcp.local."` + `"_zync._tcp.local."` ->
/// `"my-phone"`.
fn instance_name(fullname: &str, ty_domain: &str) -> String {
    let suffix = format!(".{ty_domain}");
    fullname
        .strip_suffix(&suffix)
        .unwrap_or(fullname)
        .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn instance_name_strips_service_type_suffix() {
        assert_eq!(
            instance_name("my-phone._zync._tcp.local.", "_zync._tcp.local."),
            "my-phone"
        );
    }

    #[test]
    fn instance_name_falls_back_to_fullname_when_suffix_missing() {
        assert_eq!(instance_name("weird-name", "_zync._tcp.local."), "weird-name");
    }
}
