import { get, post } from '../api.js';
import { rerender } from '../app.js';
import { escapeHtml } from './inbox.js';

export async function renderTree(el) {
  const roots = await get('/api/roots');
  el.innerHTML = `<button id="new-folder" class="outline">＋ New folder</button><section id="tree"></section>`;
  el.querySelector('#new-folder').onclick = async () => {
    const title = prompt('Folder name');
    if (title) { await post('/api/nodes', { kind: 'FOLDER', parentId: null, title }); await rerender(); }
  };
  const tree = el.querySelector('#tree');
  for (const n of roots) tree.append(nodeRow(n));
}

function nodeRow(n) {
  if (n.kind === 'TASK') return taskRow(n);
  const det = document.createElement('details');
  det.innerHTML = `<summary>${n.kind === 'FOLDER' ? '📁' : '🗂'}
      <a href="#/node/${n.id}">${escapeHtml(n.title)}</a>
      <button class="outline secondary" data-add>＋</button></summary>
    <div class="tree-children"><p class="muted">…</p></div>`;
  det.querySelector('[data-add]').onclick = async (e) => {
    e.preventDefault(); e.stopPropagation();
    const childKind = n.kind === 'FOLDER' ? 'PROJECT' : 'TASK';
    const title = prompt(`New ${childKind.toLowerCase()} in "${n.title}"`);
    if (title) { await post('/api/nodes', { kind: childKind, parentId: n.id, title }); await rerender(); }
  };
  det.addEventListener('toggle', async () => {
    if (!det.open || det.dataset.loaded) return;
    det.dataset.loaded = '1';
    const box = det.querySelector('.tree-children');
    box.innerHTML = '';
    const children = await get(`/api/nodes/${n.id}/children`);
    if (!children.length) box.innerHTML = '<p class="muted">empty</p>';
    for (const c of children) box.append(nodeRow(c));
  });
  return det;
}

function taskRow(t) {
  const row = document.createElement('div');
  row.innerHTML = `<label>
      <input type="checkbox" ${t.status === 'DONE' ? 'checked' : ''}>
      <a href="#/node/${t.id}">${escapeHtml(t.title)}</a>
      <button class="outline secondary" data-add>＋</button></label>
    <div class="tree-children"></div>`;
  row.querySelector('input').onchange = async () => {
    await post(`/api/nodes/${t.id}/${t.status === 'DONE' ? 'reopen' : 'complete'}`);
    await rerender();
  };
  row.querySelector('[data-add]').onclick = async (e) => {
    e.preventDefault();
    const title = prompt(`New subtask of "${t.title}"`);
    if (title) { await post('/api/nodes', { kind: 'TASK', parentId: t.id, title }); await rerender(); }
  };
  get(`/api/nodes/${t.id}/children`).then(kids => {
    const box = row.querySelector('.tree-children');
    for (const k of kids) box.append(nodeRow(k));
  });
  return row;
}
