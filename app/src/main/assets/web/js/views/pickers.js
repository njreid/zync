import { get } from '../api.js';

export async function pickDestination({ foldersOnly = false } = {}) {
  const all = await get('/api/destinations');
  const options = foldersOnly ? all.filter(n => n.kind === 'FOLDER') : all;
  return new Promise((resolve) => {
    const dlg = document.createElement('dialog');
    dlg.innerHTML = `<article><h4>${foldersOnly ? 'Choose folder' : 'Move to…'}</h4>
      <div id="dest-list"></div>
      <footer><button class="secondary" id="dest-cancel">Cancel</button></footer></article>`;
    document.body.append(dlg);
    const list = dlg.querySelector('#dest-list');

    let resolved = false;
    const doResolve = (value) => {
      if (!resolved) {
        resolved = true;
        dlg.remove();
        resolve(value);
      }
    };

    for (const n of options) {
      const b = document.createElement('button');
      b.className = 'outline';
      b.textContent = `${n.kind === 'FOLDER' ? '📁' : '🗂'} ${n.title}`;
      b.onclick = () => { dlg.close(); doResolve(n.id); };
      list.append(b);
    }
    dlg.querySelector('#dest-cancel').onclick = () => { dlg.close(); doResolve(null); };

    dlg.addEventListener('close', () => {
      doResolve(null);
    });

    dlg.showModal();
  });
}
