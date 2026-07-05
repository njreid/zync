import { get, post } from '../api.js';
import { pickDestination } from './pickers.js';
import { rerender } from '../app.js';

const INBOX_ID = 1;

export async function renderInbox(el) {
  const all = await get(`/api/nodes/${INBOX_ID}/children`);
  const tasks = all.filter(t => t.status === 'ACTIVE');
  el.innerHTML = `
    <form id="quick-add" role="group">
      <input id="quick-title" placeholder="Quick add to Inbox…" autocomplete="off">
      <button type="submit">Add</button>
    </form>
    <div id="native-capture" role="group" hidden>
      <button type="button" id="voice-capture" class="secondary">Voice</button>
      <button type="button" id="scan-capture" class="secondary">Scan</button>
    </div>
    <section id="inbox-list"></section>`;
  el.querySelector('#quick-add').onsubmit = async (e) => {
    e.preventDefault();
    const title = el.querySelector('#quick-title').value.trim();
    if (title) { await post('/api/inbox', { title }); await rerender(); }
  };
  wireNativeCapture(el);
  const list = el.querySelector('#inbox-list');
  if (!tasks.length) { list.innerHTML = '<p class="muted">Inbox zero ✨</p>'; return; }
  for (const t of tasks) {
    const attachments = await get(`/api/nodes/${t.id}/attachments`);
    list.append(taskCard(t, attachments));
  }
}

function taskCard(t, attachments) {
  const card = document.createElement('article');
  card.className = 'task';
  card.innerHTML = `
    <h4><a href="#/node/${t.id}">${escapeHtml(t.title)}</a></h4>
    ${t.notes ? `<p class="muted">${escapeHtml(t.notes.slice(0, 160))}</p>` : ''}
    ${attachments.length ? attachmentSummary(attachments) : ''}
    <details class="clarify"><summary role="button" class="outline secondary">Clarify</summary>
      <div role="group">
        <button data-act="done">Do ✓</button>
        <button data-act="move" class="secondary">Move…</button>
        <button data-act="project" class="secondary">Make project…</button>
        <button data-act="someday" class="secondary">Someday</button>
        <button data-act="trash" class="contrast">Trash</button>
      </div>
    </details>`;
  card.querySelector('[data-act=done]').onclick = () => act(post(`/api/nodes/${t.id}/complete`));
  card.querySelector('[data-act=someday]').onclick = () => act(post(`/api/nodes/${t.id}/move`, { parentId: 2 }));
  card.querySelector('[data-act=trash]').onclick = () => act(post(`/api/nodes/${t.id}/trash`));
  card.querySelector('[data-act=move]').onclick = async () => {
    const dest = await pickDestination();
    if (dest != null) await act(post(`/api/nodes/${t.id}/move`, { parentId: dest }));
  };
  card.querySelector('[data-act=project]').onclick = async () => {
    const folder = await pickDestination({ foldersOnly: true });
    if (folder != null) await act(post(`/api/nodes/${t.id}/convert`, { folderId: folder }));
  };
  return card;
}

async function act(promise) { await promise; await rerender(); }

function wireNativeCapture(el) {
  const nativeCapture = window.ZyncCapture;
  if (!nativeCapture) return;
  const panel = el.querySelector('#native-capture');
  panel.hidden = false;
  const voice = el.querySelector('#voice-capture');
  const scan = el.querySelector('#scan-capture');
  if (typeof nativeCapture.startVoiceNote === 'function') {
    voice.onclick = () => nativeCapture.startVoiceNote();
  } else {
    voice.hidden = true;
  }
  if (typeof nativeCapture.startDocumentScan === 'function') {
    scan.onclick = () => nativeCapture.startDocumentScan();
  } else {
    scan.hidden = true;
  }
}

function attachmentSummary(attachments) {
  const label = attachments.length === 1 ? '1 attachment' : `${attachments.length} attachments`;
  return `<p class="muted">${escapeHtml(label)}</p>`;
}

export function escapeHtml(s) {
  return s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
