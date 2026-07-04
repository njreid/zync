// Startup + screen orchestration for the desktop pairing/connection UI.
// Flow: discover() phones -> either auto-connect (if the previously-paired
// phone is discoverable) or list them with a Pair button each -> pairing.js
// drives the QR/confirm-code/paired/pair-failed sequence -> connect() ->
// navigate the whole window into the phone's web app via the proxy.

import { invoke } from "./tauri.js";
import { startPairing } from "./pairing.js";

const STORAGE_KEY = "zync:pairedPhoneName";

const statusLine = document.getElementById("status-line");
const views = {
  discover: document.getElementById("view-discover"),
  qr: document.getElementById("view-qr"),
  confirm: document.getElementById("view-confirm"),
  error: document.getElementById("view-error"),
  connecting: document.getElementById("view-connecting"),
};
const discoverEmpty = document.getElementById("discover-empty");
const phoneList = document.getElementById("phone-list");
const qrImage = document.getElementById("qr-image");
const confirmCodeEl = document.getElementById("confirm-code");
const errorMessageEl = document.getElementById("error-message");
const footerActions = document.getElementById("footer-actions");
const forgetButton = document.getElementById("forget-button");
const rescanButton = document.getElementById("rescan-button");
const cancelPairButton = document.getElementById("cancel-pair-button");
const retryButton = document.getElementById("retry-button");

let currentCleanup = null; // unlisten fn for the in-flight pairing attempt, if any
let lastAttemptedPhoneName = null; // for the "Try again" retry action

function showView(name) {
  for (const [key, el] of Object.entries(views)) {
    el.hidden = key !== name;
  }
}

function setStatus(text) {
  statusLine.textContent = text;
}

function getStoredPairedName() {
  return localStorage.getItem(STORAGE_KEY);
}

function setStoredPairedName(name) {
  localStorage.setItem(STORAGE_KEY, name);
}

function clearStoredPairedName() {
  localStorage.removeItem(STORAGE_KEY);
}

function cancelInFlightPairing() {
  if (currentCleanup) {
    currentCleanup();
    currentCleanup = null;
  }
}

function renderPhoneList(phones) {
  phoneList.innerHTML = "";
  discoverEmpty.hidden = phones.length > 0;

  for (const phone of phones) {
    const li = document.createElement("li");

    const label = document.createElement("span");
    const nameEl = document.createElement("span");
    nameEl.className = "phone-name";
    nameEl.textContent = phone.name;
    label.appendChild(nameEl);
    if (phone.fp_hint) {
      const fpEl = document.createElement("span");
      fpEl.className = "phone-fp";
      fpEl.textContent = ` (${phone.fp_hint})`;
      label.appendChild(fpEl);
    }

    const pairButton = document.createElement("button");
    pairButton.type = "button";
    pairButton.textContent = "Pair";
    pairButton.addEventListener("click", () => beginPairing(phone.name));

    li.appendChild(label);
    li.appendChild(pairButton);
    phoneList.appendChild(li);
  }
}

async function runDiscovery() {
  showView("discover");
  setStatus("Looking for your phone…");
  phoneList.innerHTML = "";
  discoverEmpty.hidden = true;

  let phones = [];
  try {
    phones = await invoke("discover");
  } catch (err) {
    setStatus(`Discovery failed: ${err}`);
    return;
  }

  const storedName = getStoredPairedName();
  if (storedName && phones.some((p) => p.name === storedName)) {
    setStatus(`Found ${storedName} — connecting…`);
    await connectAndNavigate(storedName);
    return;
  }

  setStatus(phones.length > 0 ? "Choose a phone to pair with:" : "No phones found yet.");
  renderPhoneList(phones);
  footerActions.hidden = !storedName;
}

async function connectAndNavigate(phoneName) {
  showView("connecting");
  try {
    const url = await invoke("connect", { phoneName });
    setStoredPairedName(phoneName);
    window.location = url;
  } catch (err) {
    clearStoredPairedName();
    showError(`Could not connect to ${phoneName}: ${err}`);
  }
}

function showError(message) {
  errorMessageEl.textContent = message;
  showView("error");
}

async function beginPairing(phoneName) {
  cancelInFlightPairing();
  lastAttemptedPhoneName = phoneName;

  qrImage.removeAttribute("src");
  confirmCodeEl.textContent = "";
  showView("qr");
  setStatus(`Pairing with ${phoneName}…`);

  currentCleanup = await startPairing(phoneName, {
    onQrPayload: (dataUri) => {
      qrImage.src = dataUri;
      showView("qr");
    },
    onConfirmCode: (code) => {
      confirmCodeEl.textContent = code;
      showView("confirm");
    },
    onPaired: async (pairedName) => {
      cancelInFlightPairing();
      await connectAndNavigate(pairedName);
    },
    onFailed: (message) => {
      cancelInFlightPairing();
      showError(message);
    },
  });
}

async function forgetCurrentPhone() {
  const name = getStoredPairedName();
  if (!name) return;
  try {
    await invoke("forget", { phoneName: name });
  } finally {
    clearStoredPairedName();
    footerActions.hidden = true;
    await runDiscovery();
  }
}

rescanButton.addEventListener("click", () => runDiscovery());
forgetButton.addEventListener("click", () => forgetCurrentPhone());
cancelPairButton.addEventListener("click", () => {
  cancelInFlightPairing();
  runDiscovery();
});
retryButton.addEventListener("click", () => {
  if (lastAttemptedPhoneName) {
    beginPairing(lastAttemptedPhoneName);
  } else {
    runDiscovery();
  }
});

runDiscovery();
