pub mod identity;
pub mod discovery;
pub mod pinning;
pub mod pairing;
pub mod paired_store;
pub mod proxy;
pub mod commands;

use commands::AppState;

// Learn more about Tauri commands at https://tauri.app/develop/calling-rust/
#[tauri::command]
fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .manage(AppState::default())
        .invoke_handler(tauri::generate_handler![
            greet,
            commands::discover,
            commands::pair,
            commands::connection_state,
            commands::forget,
            commands::proxy_url
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
