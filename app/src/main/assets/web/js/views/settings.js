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
      <h3>Paired devices</h3>
      <div id="device-list"><p>Loading…</p></div>
    </article>
    <article>
      <h3>Backup</h3>
      <label>
        <input type="checkbox" id="backup-toggle" role="switch">
        Automatic encrypted backup to Google Drive
      </label>
      <input type="password" id="backup-passphrase" autocomplete="new-password" placeholder="Backup passphrase">
      <div class="button-row">
        <button id="backup-connect" class="secondary">Connect Google Drive</button>
        <button id="backup-save">Save</button>
        <button id="backup-now" class="secondary">Back up now</button>
        <button id="backup-restore-next" class="secondary">Restore on next launch</button>
      </div>
      <p id="backup-info"></p>
    </article>
  `;

  const toggle = el.querySelector('#remote-toggle');
  const remoteInfo = el.querySelector('#remote-info');
  const pairResult = el.querySelector('#pair-result');
  const deviceList = el.querySelector('#device-list');
  const backupToggle = el.querySelector('#backup-toggle');
  const backupPassphrase = el.querySelector('#backup-passphrase');
  const backupInfo = el.querySelector('#backup-info');

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

  function renderBackupState(state) {
    backupToggle.checked = state.enabled;
    const parts = [
      state.enabled ? 'On' : 'Off',
      state.hasPassphrase ? 'passphrase saved' : 'passphrase required',
    ];
    if (state.lastSuccessAt) parts.push(`last backup ${new Date(state.lastSuccessAt).toLocaleString()}`);
    if (state.lastBackupName) parts.push(state.lastBackupName);
    if (state.restorePending) parts.push('restore pending for next launch');
    if (state.lastError) parts.push(`last error: ${state.lastError}`);
    backupInfo.textContent = parts.join(' — ');
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

  async function refreshBackup() {
    try {
      renderBackupState(await get('/backup/state'));
    } catch (e) {
      backupInfo.textContent = `Backup unavailable: ${e.message}`;
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

  el.querySelector('#backup-connect').onclick = () => {
    if (!window.ZyncBackup || typeof window.ZyncBackup.connectGoogleDrive !== 'function') {
      backupInfo.textContent = 'Google Drive connection is only available in the zync app.';
      return;
    }
    backupInfo.textContent = 'Connecting Google Drive…';
    window.__zyncGoogleDriveResult = async (email, error) => {
      if (error) {
        backupInfo.textContent = `Google Drive connection failed: ${error}`;
        return;
      }
      backupInfo.textContent = `Connected Google Drive${email ? ` as ${email}` : ''}.`;
      await refreshBackup();
    };
    window.ZyncBackup.connectGoogleDrive();
  };

  el.querySelector('#backup-save').onclick = async () => {
    try {
      const body = { enabled: backupToggle.checked };
      if (backupPassphrase.value.trim()) body.passphrase = backupPassphrase.value;
      renderBackupState(await post('/backup/config', body));
      backupPassphrase.value = '';
    } catch (e) {
      toast(e.message);
      await refreshBackup();
    }
  };

  el.querySelector('#backup-now').onclick = async () => {
    try {
      backupInfo.textContent = 'Backing up…';
      renderBackupState(await post('/backup/now'));
    } catch (e) {
      toast(e.message);
      await refreshBackup();
    }
  };

  el.querySelector('#backup-restore-next').onclick = async () => {
    try {
      renderBackupState(await post('/backup/restore-next-launch'));
    } catch (e) {
      toast(e.message);
      await refreshBackup();
    }
  };

  await refreshState();
  await refreshDevices();
  await refreshBackup();
}
