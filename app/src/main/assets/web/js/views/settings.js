import { get, post } from '../api.js';
import { toast } from '../app.js';

function formatLastSeen(lastSeen) {
  return lastSeen ? new Date(lastSeen).toLocaleString() : 'never';
}

export async function renderSettings(el) {
  el.innerHTML = `
    <article>
      <h3>Remote access</h3>
      <label>
        <input type="checkbox" id="remote-toggle" role="switch">
        Allow paired desktops to connect over the LAN
      </label>
      <p id="remote-info"></p>
    </article>
    <article>
      <h3>Pair a browser</h3>
      <button id="pair-scan" class="secondary">Scan QR from desktop</button>
      <p id="pair-result"></p>
    </article>
    <article>
      <h3>Quick capture gesture</h3>
      <p>Capture into your Inbox from anywhere:<br>
        double-press <strong>Volume Up</strong> to record a voice note,
        double-press <strong>Volume Down</strong> to scan a document.</p>
      <button id="enable-capture" class="secondary">Enable in Accessibility settings</button>
      <p id="capture-info"></p>
    </article>
    <article>
      <h3>Paired devices</h3>
      <div id="device-list"><p>Loading…</p></div>
    </article>
  `;

  const toggle = el.querySelector('#remote-toggle');
  const remoteInfo = el.querySelector('#remote-info');
  const pairResult = el.querySelector('#pair-result');
  const deviceList = el.querySelector('#device-list');

  function renderRemoteState(state) {
    toggle.checked = state.enabled;
    remoteInfo.textContent = state.enabled
      ? `${state.ip}:${state.tlsPort} — fingerprint ${state.certFingerprint}`
      : 'Off — only this device can access zync.';
  }

  function renderDevices(devices) {
    if (devices.length === 0) {
      deviceList.innerHTML = '<p>No paired devices yet.</p>';
      return;
    }
    deviceList.innerHTML = '';
    for (const d of devices) {
      const row = document.createElement('article');
      row.className = 'device-row';
      row.dataset.deviceId = String(d.id);

      const details = document.createElement('div');
      details.innerHTML = `<strong>${d.name}</strong>${d.revoked ? ' <em>(revoked)</em>' : ''}
        <br><small>Last seen: ${formatLastSeen(d.lastSeen)}</small>`;
      row.append(details);

      if (!d.revoked) {
        const revokeBtn = document.createElement('button');
        revokeBtn.className = 'secondary';
        revokeBtn.dataset.act = 'revoke';
        revokeBtn.textContent = 'Revoke';
        revokeBtn.onclick = async () => {
          try {
            await post(`/devices/${d.id}/revoke`);
            await refreshDevices();
          } catch (e) {
            toast(e.message);
          }
        };
        row.append(revokeBtn);
      }
      deviceList.append(row);
    }
  }

  async function refreshState() {
    try {
      renderRemoteState(await get('/remote/state'));
    } catch (e) {
      toast(e.message);
    }
  }

  async function refreshDevices() {
    try {
      renderDevices(await get('/devices'));
    } catch (e) {
      toast(e.message);
    }
  }

  toggle.onchange = async () => {
    const wantEnabled = toggle.checked;
    try {
      if (wantEnabled) {
        const info = await post('/remote/enable');
        renderRemoteState({ enabled: true, ip: info.ip, tlsPort: info.tlsPort, certFingerprint: info.certFingerprint });
      } else {
        await post('/remote/disable');
        renderRemoteState({ enabled: false });
      }
    } catch (e) {
      toggle.checked = !wantEnabled;
      toast(e.message);
    }
  };

  el.querySelector('#pair-scan').onclick = () => {
    if (!window.ZyncNative || typeof window.ZyncNative.scanPairingQr !== 'function') {
      pairResult.textContent = 'QR scanning is only available in the zync app.';
      return;
    }
    pairResult.textContent = 'Scanning…';
    window.__zyncQrScanResult = async (payload, error) => {
      if (error) {
        pairResult.textContent = `Scan failed: ${error}`;
        return;
      }
      try {
        const result = await post('/pair/approve', { payload });
        pairResult.textContent = `Confirm code: ${result.confirmCode} — check it matches the desktop.`;
        await refreshDevices();
      } catch (e) {
        pairResult.textContent = `Pairing failed: ${e.message}`;
      }
    };
    window.ZyncNative.scanPairingQr();
  };

  const captureInfo = el.querySelector('#capture-info');
  el.querySelector('#enable-capture').onclick = () => {
    if (!window.ZyncCapture || typeof window.ZyncCapture.openAccessibilitySettings !== 'function') {
      captureInfo.textContent = 'The capture gesture is only available in the zync app.';
      return;
    }
    window.ZyncCapture.openAccessibilitySettings();
    captureInfo.textContent = 'Find "zync quick capture" in the list and turn it on.';
  };

  await refreshState();
  await refreshDevices();
}
