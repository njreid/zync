import { get, post, patch, put } from '../api.js';
import { rerender, toast } from '../app.js';

const DAY = 86400000;

function dirtyKey(id) { return `dirty:${id}`; }

function loadDirty(id) {
  try {
    const raw = sessionStorage.getItem(dirtyKey(id));
    return raw ? JSON.parse(raw) : null;
  } catch { return null; }
}

function saveDirty(id, title, notes) {
  sessionStorage.setItem(dirtyKey(id), JSON.stringify({ title, notes }));
}

function clearDirty(id) {
  sessionStorage.removeItem(dirtyKey(id));
}

export async function renderDetail(el, id) {
  let node;
  try { node = await get(`/api/nodes/${id}`); }
  catch (e) { toast(e.message); location.hash = '#/inbox'; return; }
  const [mine, all, attachments] = await Promise.all([
    get(`/api/nodes/${id}/contexts`), get('/api/contexts'), get(`/api/nodes/${id}/attachments`),
  ]);
  const mineIds = new Set(mine.map(c => c.id));

  el.innerHTML = `
    <a href="#/inbox">← Inbox</a>
    <form id="edit">
      <label>Title <input id="f-title" required></label>
      <label>Notes <textarea id="f-notes" rows="6"></textarea></label>
      <div role="group">
        <button type="submit">Save</button>
        <button type="button" id="f-done" class="${node.status === 'DONE' ? 'secondary' : 'contrast'}">
          ${node.status === 'DONE' ? 'Reopen' : 'Done ✓'}</button>
      </div>
    </form>
    <h5>Attachments</h5><div id="f-attachments"></div>
    <h5>Contexts</h5><div class="chips" id="f-chips"></div>
    <h5>Defer</h5>
    <div role="group">
      <button class="outline" data-defer="1">+1 day</button>
      <button class="outline" data-defer="7">+1 week</button>
      <button class="outline secondary" data-defer="clear">Clear</button>
    </div>
    <p class="muted" id="f-defer-state"></p>`;

  const titleEl = el.querySelector('#f-title');
  const notesEl = el.querySelector('#f-notes');

  titleEl.value = node.title;
  notesEl.value = node.notes;

  // Re-apply any unsaved edits left over from before this render (e.g. a
  // background push or an in-view action triggered a rerender while dirty).
  const pending = loadDirty(id);
  if (pending) {
    titleEl.value = pending.title;
    notesEl.value = pending.notes;
  }

  const isDirty = () =>
    titleEl.value !== node.title || notesEl.value !== node.notes;

  const persistDirty = () => saveDirty(id, titleEl.value, notesEl.value);
  titleEl.addEventListener('input', persistDirty);
  notesEl.addEventListener('input', persistDirty);
  if (pending) persistDirty();

  el.querySelector('#f-defer-state').textContent =
    node.deferUntil ? `Deferred until ${new Date(node.deferUntil).toLocaleDateString()}` : '';

  renderAttachments(el.querySelector('#f-attachments'), attachments);

  // Flush any dirty title/notes to the server before performing an action
  // that would otherwise trigger a rerender and discard them.
  const flushIfDirty = async () => {
    if (!isDirty()) return;
    await patch(`/api/nodes/${id}`, {
      title: titleEl.value.trim(),
      notes: notesEl.value,
    });
    node.title = titleEl.value.trim();
    node.notes = notesEl.value;
    clearDirty(id);
  };

  el.querySelector('#edit').onsubmit = async (e) => {
    e.preventDefault();
    await patch(`/api/nodes/${id}`, {
      title: titleEl.value.trim(),
      notes: notesEl.value,
    });
    clearDirty(id);
    await rerender();
  };
  el.querySelector('#f-done').onclick = async () => {
    await flushIfDirty();
    await post(`/api/nodes/${id}/${node.status === 'DONE' ? 'reopen' : 'complete'}`);
    await rerender();
  };
  for (const btn of el.querySelectorAll('[data-defer]')) {
    btn.onclick = async () => {
      await flushIfDirty();
      const v = btn.dataset.defer;
      await post(`/api/nodes/${id}/defer`,
        { until: v === 'clear' ? null : Date.now() + Number(v) * DAY });
      await rerender();
    };
  }
  const chips = el.querySelector('#f-chips');
  if (!all.length) chips.innerHTML = '<p class="muted">No contexts defined</p>';
  for (const c of all) {
    const b = document.createElement('button');
    b.textContent = `@${c.name}`;
    b.setAttribute('aria-pressed', String(mineIds.has(c.id)));
    b.onclick = async () => {
      await flushIfDirty();
      const next = new Set(mineIds);
      next.has(c.id) ? next.delete(c.id) : next.add(c.id);
      await put(`/api/nodes/${id}/contexts`, { contextIds: [...next] });
      await rerender();
    };
    chips.append(b);
  }
}

function renderAttachments(el, attachments) {
  if (!attachments.length) {
    el.innerHTML = '<p class="muted">No attachments</p>';
    return;
  }
  const list = document.createElement('ul');
  for (const attachment of attachments) {
    const item = document.createElement('li');
    const link = document.createElement('a');
    link.href = attachment.downloadUrl;
    link.textContent = `${attachment.type.toLowerCase().replace('_', ' ')}: ${attachment.relativePath}`;
    item.append(link);
    list.append(item);
  }
  el.replaceChildren(list);
}
