import { get, post, patch, put } from '../api.js';
import { rerender, toast } from '../app.js';

const DAY = 86400000;

export async function renderDetail(el, id) {
  let node;
  try { node = await get(`/api/nodes/${id}`); }
  catch (e) { toast(e.message); location.hash = '#/inbox'; return; }
  const [mine, all] = await Promise.all([
    get(`/api/nodes/${id}/contexts`), get('/api/contexts'),
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
    <h5>Contexts</h5><div class="chips" id="f-chips"></div>
    <h5>Defer</h5>
    <div role="group">
      <button class="outline" data-defer="1">+1 day</button>
      <button class="outline" data-defer="7">+1 week</button>
      <button class="outline secondary" data-defer="clear">Clear</button>
    </div>
    <p class="muted" id="f-defer-state"></p>`;

  el.querySelector('#f-title').value = node.title;
  el.querySelector('#f-notes').value = node.notes;
  el.querySelector('#f-defer-state').textContent =
    node.deferUntil ? `Deferred until ${new Date(node.deferUntil).toLocaleDateString()}` : '';

  el.querySelector('#edit').onsubmit = async (e) => {
    e.preventDefault();
    await patch(`/api/nodes/${id}`, {
      title: el.querySelector('#f-title').value.trim(),
      notes: el.querySelector('#f-notes').value,
    });
    await rerender();
  };
  el.querySelector('#f-done').onclick = async () => {
    await post(`/api/nodes/${id}/${node.status === 'DONE' ? 'reopen' : 'complete'}`);
    await rerender();
  };
  for (const btn of el.querySelectorAll('[data-defer]')) {
    btn.onclick = async () => {
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
      const next = new Set(mineIds);
      next.has(c.id) ? next.delete(c.id) : next.add(c.id);
      await put(`/api/nodes/${id}/contexts`, { contextIds: [...next] });
      await rerender();
    };
    chips.append(b);
  }
}
