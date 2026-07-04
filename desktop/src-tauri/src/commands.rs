//! Tauri commands the webview calls via `invoke`. Each command is a thin
//! wrapper (parameter/`AppHandle`/`State` plumbing + error-to-`String`
//! mapping, since `Result<_, String>` is what a Tauri command needs to
//! surface a rejection to JS) around a plain, unit-testable `*_logic`
//! function that takes only ordinary values/trait objects and returns
//! `anyhow::Result`. The wrappers themselves are compile-checked only (they
//! need a real `tauri::AppHandle`/`tauri::State`, which aren't practical to
//! construct in a unit test) — see the brief's "known gaps" note in the
//! task report.

use crate::discovery::{self, DiscoveredPhone};
use crate::identity::DeviceIdentity;
use crate::paired_store::{self, PairedStore};
use crate::pairing::{self, PairedPhone};
use crate::proxy::{start_proxy, ProxyHandle};
use anyhow::{anyhow, Result};
use std::sync::Mutex;
use std::time::Duration;

/// Default mDNS browse window used by the `discover` command.
const DISCOVER_TIMEOUT: Duration = Duration::from_secs(3);

/// Managed Tauri state: the currently-running proxy (if paired and
/// connected), the last set of discovered phones (so `pair` can look one up
/// by name without re-browsing), and this device's identity.
#[derive(Default)]
pub struct AppState {
    pub proxy: Mutex<Option<ProxyHandle>>,
    pub last_discovered: Mutex<Vec<DiscoveredPhone>>,
}

/// What the UI needs to know about the current connection.
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct ConnectionState {
    pub connected: bool,
    pub proxy_port: Option<u16>,
}

// ---------------------------------------------------------------------
// Pure/testable logic functions
// ---------------------------------------------------------------------

/// Browse the LAN for `timeout` and return every phone found.
pub fn discover_logic(timeout: Duration) -> Result<Vec<DiscoveredPhone>> {
    discovery::browse(timeout)
}

/// Drive the pairing handshake against `phone`, forwarding the confirm code
/// to `on_confirm_code` (so a real command wrapper can `app.emit("confirm-code",
/// ..)`), then persist the result under `phone_name` via `store`.
pub async fn pair_logic(
    id: &DeviceIdentity,
    phone: &DiscoveredPhone,
    phone_name: &str,
    store: &dyn PairedStore,
    on_confirm_code: impl Fn(&str),
) -> Result<PairedPhone> {
    let paired = pairing::pair(id, phone, on_confirm_code).await?;
    store.save(phone_name, &paired)?;
    Ok(paired)
}

/// Build the connection-state snapshot the UI polls/renders.
pub fn connection_state_logic(proxy: &Option<ProxyHandle>) -> ConnectionState {
    match proxy {
        Some(handle) => ConnectionState { connected: true, proxy_port: Some(handle.port) },
        None => ConnectionState { connected: false, proxy_port: None },
    }
}

/// Forget a previously-paired phone.
pub fn forget_logic(store: &dyn PairedStore, name: &str) -> Result<()> {
    store.forget(name)
}

/// The URL the webview should navigate to once a proxy is running on
/// `port`.
pub fn proxy_url_logic(port: u16) -> String {
    format!("http://127.0.0.1:{port}/")
}

/// Start the proxy for a phone that's already paired and persisted under
/// `name`, returning the URL the webview should load.
pub async fn connect_logic(store: &dyn PairedStore, name: &str) -> Result<(ProxyHandle, String)> {
    let paired = store
        .load(name)?
        .ok_or_else(|| anyhow!("no paired phone named {name:?}"))?;
    let handle = start_proxy(paired).await?;
    let url = proxy_url_logic(handle.port);
    Ok((handle, url))
}

// ---------------------------------------------------------------------
// Thin #[tauri::command] wrappers — compile-checked only; logic lives above.
// ---------------------------------------------------------------------

#[tauri::command]
pub async fn discover() -> Result<Vec<DiscoveredPhone>, String> {
    tauri::async_runtime::spawn_blocking(|| discover_logic(DISCOVER_TIMEOUT))
        .await
        .map_err(|e| e.to_string())?
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn pair(
    app: tauri::AppHandle,
    state: tauri::State<'_, AppState>,
    phone_name: String,
) -> Result<(), String> {
    use tauri::Emitter;

    let phone = {
        let discovered = state.last_discovered.lock().unwrap();
        discovered.iter().find(|p| p.name == phone_name).cloned()
    };
    let Some(phone) = phone else {
        let _ = app.emit("pair-failed", format!("unknown phone: {phone_name}"));
        return Err(format!("unknown phone: {phone_name}"));
    };

    let id = DeviceIdentity::load_or_create("zync-desktop").map_err(|e| e.to_string())?;
    let store = paired_store::KeychainPairedStore;

    let app_for_code = app.clone();
    let result = pair_logic(&id, &phone, &phone_name, &store, move |code| {
        let _ = app_for_code.emit("confirm-code", code);
    })
    .await;

    match result {
        Ok(_paired) => {
            let _ = app.emit("paired", &phone_name);
            Ok(())
        }
        Err(e) => {
            let _ = app.emit("pair-failed", e.to_string());
            Err(e.to_string())
        }
    }
}

#[tauri::command]
pub fn connection_state(state: tauri::State<'_, AppState>) -> ConnectionState {
    connection_state_logic(&state.proxy.lock().unwrap())
}

#[tauri::command]
pub fn forget(phone_name: String) -> Result<(), String> {
    let store = paired_store::KeychainPairedStore;
    forget_logic(&store, &phone_name).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn proxy_url(state: tauri::State<'_, AppState>) -> Result<String, String> {
    let proxy = state.proxy.lock().unwrap();
    match proxy.as_ref() {
        Some(handle) => Ok(proxy_url_logic(handle.port)),
        None => Err("no proxy is currently running".to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::paired_store::InMemoryPairedStore;

    #[test]
    fn proxy_url_logic_formats_localhost() {
        assert_eq!(proxy_url_logic(4123), "http://127.0.0.1:4123/");
    }

    #[test]
    fn connection_state_logic_reports_disconnected_when_no_proxy() {
        let state = connection_state_logic(&None);
        assert!(!state.connected);
        assert_eq!(state.proxy_port, None);
    }

    #[test]
    fn forget_logic_delegates_to_store() {
        let store = InMemoryPairedStore::default();
        // Nothing to forget yet — should be a no-op Ok, not an error.
        assert!(forget_logic(&store, "nope").is_ok());
    }
}
