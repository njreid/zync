async function request(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : {},
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let msg = `${res.status}`;
    try { msg = (await res.json()).error ?? msg; } catch { /* not json */ }
    throw new Error(msg);
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export const get = (p) => request('GET', p);
export const post = (p, b) => request('POST', p, b);
export const patch = (p, b) => request('PATCH', p, b);
export const put = (p, b) => request('PUT', p, b);

export function connectEvents(onChanged) {
  const ws = new WebSocket(`ws://${location.host}/api/events`);
  ws.onmessage = (e) => { if (JSON.parse(e.data).type === 'changed') onChanged(); };
  ws.onclose = () => setTimeout(() => connectEvents(onChanged), 2000);
}
