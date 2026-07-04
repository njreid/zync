// Thin wrapper around the globally-injected Tauri v2 API
// (`tauri.conf.json` sets `app.withGlobalTauri: true`), so the rest of the
// UI code can `import { invoke, listen } from "./tauri.js"` instead of
// reaching into `window.__TAURI__` directly.

function tauri() {
  const t = window.__TAURI__;
  if (!t) {
    throw new Error("window.__TAURI__ is not available — is this running inside the Tauri webview?");
  }
  return t;
}

/** Invoke a Tauri command by name, returning its resolved value. */
export function invoke(cmd, args) {
  return tauri().core.invoke(cmd, args);
}

/**
 * Subscribe to a Tauri event. Returns a Promise resolving to an `unlisten`
 * function.
 */
export function listen(event, handler) {
  return tauri().event.listen(event, (e) => handler(e.payload));
}
