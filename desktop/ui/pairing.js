// Drives one pairing attempt against a discovered phone: registers Tauri
// event listeners for the `pair` command's `qr-payload` / `confirm-code` /
// `paired` / `pair-failed` events, then kicks off `pair(phoneName)`.
//
// Callbacks are invoked at most once each per attempt (except `onQrPayload`
// and `onConfirmCode`, which the Rust side only ever emits once per attempt
// too, but are not restricted to exactly-once here).

import { invoke, listen } from "./tauri.js";

/**
 * @param {string} phoneName
 * @param {{
 *   onQrPayload: (dataUri: string) => void,
 *   onConfirmCode: (code: string) => void,
 *   onPaired: (phoneName: string) => void,
 *   onFailed: (message: string) => void,
 * }} callbacks
 * @returns {Promise<() => void>} a cleanup function that removes all
 *   listeners registered for this attempt.
 */
export async function startPairing(phoneName, callbacks) {
  const unlistenFns = await Promise.all([
    listen("qr-payload", (dataUri) => callbacks.onQrPayload(dataUri)),
    listen("confirm-code", (code) => callbacks.onConfirmCode(code)),
    listen("paired", (pairedName) => callbacks.onPaired(pairedName)),
    listen("pair-failed", (message) => callbacks.onFailed(message)),
  ]);

  const cleanup = () => {
    for (const unlisten of unlistenFns) unlisten();
  };

  try {
    await invoke("pair", { phoneName });
  } catch (err) {
    // The Rust command also emits `pair-failed` on error, so the UI has
    // almost certainly already reacted via that event by the time this
    // rejection is observed. This catch only guards against an unhandled
    // promise rejection in the case the command failed before emitting.
    callbacks.onFailed(String(err));
  }

  return cleanup;
}
