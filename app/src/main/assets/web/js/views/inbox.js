import { get, post } from '../api.js';
import { pickDestination } from './pickers.js';
import { rerender } from '../app.js';

const INBOX_ID = 1;

export async function renderInbox(el) {
  const tasks = await get(`/api/nodes/${INBOX_ID}/children`);
  el.innerHTML = `
    <form id="quick-add" role="group">
      <input id="quick-title" placeholder="Quick add to Inbox…" autocomplete="off">
      <button type="submit">Add</button>
    </form>
    <section id="inbox-list"></section>`;
  el.querySelector('#quick-add').onsubmit = async (e) => {
    e.preventDefault();
    const title = el.querySelector('#quick-title').value.trim();
    if (title) { await post('/api/inbox', { title }); await rerender(); }
  };
  const list = el.querySelector('#inbox-list');
  if (!tasks.length) { list.innerHTML = '<p class="muted">Inbox zero ✨</p>'; return; }
  for (const t of tasks) list.append(taskCard(t));
}

function taskCard(t) {
  const card = document.createElement('article');
  card.className = 'task';
  card.innerHTML = `
    <h4><a href="#/node/${t.id}">${escapeHtml(t.title)}</a></h4>
    ${t.notes ? `<p class="muted">${escapeHtml(t.notes.slice(0, 160))}</p>` : ''}
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

export function escapeHtml(s) {
  return s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
