import { get, post } from '../api.js';
import { rerender } from '../app.js';
import { escapeHtml } from './inbox.js';

export async function renderContexts(el) {
  const contexts = await get('/api/contexts');
  const selected = Number(sessionStorage.getItem('ctx') || 0) || null;
  el.innerHTML = `
    <div role="group">
      <button id="new-context" class="outline">＋ New context</button>
    </div>
    <div class="chips" id="chips"></div>
    <section id="ctx-tasks"></section>`;
  el.querySelector('#new-context').onclick = async () => {
    const name = prompt('Context name (without @)');
    if (name) { await post('/api/contexts', { name }); await rerender(); }
  };
  const chips = el.querySelector('#chips');
  if (!contexts.length) { chips.innerHTML = '<p class="muted">No contexts yet</p>'; return; }
  for (const c of contexts) {
    const b = document.createElement('button');
    b.textContent = `@${c.name}`;
    b.setAttribute('aria-pressed', String(c.id === selected));
    b.onclick = () => {
      sessionStorage.setItem('ctx', c.id === selected ? '' : String(c.id));
      rerender();
    };
    chips.append(b);
  }
  if (selected == null) return;
  const tasks = await get(`/api/contexts/${selected}/tasks`);
  const list = el.querySelector('#ctx-tasks');
  if (!tasks.length) { list.innerHTML = '<p class="muted">Nothing actionable here</p>'; return; }
  for (const t of tasks) {
    const row = document.createElement('article');
    row.className = 'task';
    row.innerHTML = `<label><input type="checkbox">
      <a href="#/node/${t.id}">${escapeHtml(t.title)}</a></label>`;
    row.querySelector('input').onchange = async () => {
      await post(`/api/nodes/${t.id}/complete`); await rerender();
    };
    list.append(row);
  }
}
