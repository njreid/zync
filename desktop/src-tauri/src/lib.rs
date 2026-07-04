#[allow(dead_code)] // wired up by a later pairing task
pub mod identity;
#[allow(dead_code)] // wired up by a later UI-facing task
pub mod discovery;
#[allow(dead_code)] // wired up by a later proxy task
pub mod pinning;
#[allow(dead_code)] // wired up by a later commands/UI task
pub mod pairing;

// Learn more about Tauri commands at https://tauri.app/develop/calling-rust/
#[tauri::command]
fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![greet])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
