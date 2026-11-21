// WatchLater+ web: a small "inbox" per person, so a link shared from the iPhone Share menu
// (through a Shortcut) reaches the Home Screen app, which keeps its list separately from Safari.
// Each person has a private code; each shared link is its own small entry, picked up and
// removed the next time the app opens.

const CODE = /^[A-Za-z0-9]{16,40}$/;
const MAX_WAITING = 100;

export const validCode = k => CODE.test(String(k || ''));

/** The first web link inside whatever was shared ("" when there is none). */
export function linkIn(raw) {
  let s = String(raw || '').trim();
  if (!s) return '';
  // an encoded link arrives as https%3A%2F%2F...
  if (/^https?%3A/i.test(s)) { try { s = decodeURIComponent(s); } catch (e) { /* keep as is */ } }
  const m = /https?:\/\/[^\s"'<>]+/i.exec(s);
  if (!m || m[0].length > 2000) return '';
  try { const u = new URL(m[0]); return u.hostname.includes('.') ? u.toString() : ''; } catch (e) { return ''; }
}

/** What was shared, from the address (?add=... must be the last part) or from the request body. */
export async function sharedText(req) {
  const search = new URL(req.url).search;
  const at = search.indexOf('add=');
  if (at >= 0) {
    // everything after add= is the link, even when the Shortcut did not encode its own ? and &
    const tail = search.slice(at + 4);
    if (/^https?%3A/i.test(tail)) { try { return decodeURIComponent(tail.split('&')[0]); } catch (e) { /* fall through */ } }
    try { return decodeURI(tail); } catch (e) { return tail; }
  }
  if (req.method !== 'POST') return '';
  const type = (req.headers.get('content-type') || '').toLowerCase();
  try {
    if (type.includes('json')) { const j = await req.json(); return String((j && (j.add || j.url || j.text)) || ''); }
    if (type.includes('form')) { const f = await req.formData(); return String(f.get('add') || f.get('url') || f.get('text') || ''); }
    return (await req.text()).slice(0, 4000);
  } catch (e) { return ''; }
}

const stamp = key => Number(String(key).split('/')[1].split('-')[0]) || 0;

/** store: Netlify Blobs store (get / setJSON / list / delete). */
export async function handle(req, store) {
  const q = new URL(req.url).searchParams;
  const k = q.get('k') || '';
  if (!validCode(k)) return new Response('This link is not complete. Copy it again from WatchLater+ Settings.', { status: 400 });
  const prefix = k + '/';

  if (q.has('list')) {
    const { blobs } = await store.list({ prefix });
    const keys = blobs.map(b => b.key).sort().slice(0, MAX_WAITING);
    const items = [];
    for (const key of keys) {
      const v = await store.get(key, { type: 'json' });
      if (v && v.u) items.push({ u: v.u, a: stamp(key) });
    }
    return Response.json({ items }, { headers: { 'cache-control': 'no-store' } });
  }

  if (q.has('done')) {
    const upTo = Number(q.get('done')) || 0;
    const { blobs } = await store.list({ prefix });
    let n = 0;
    for (const b of blobs) if (stamp(b.key) <= upTo) { await store.delete(b.key); n++; }
    return Response.json({ ok: true, removed: n }, { headers: { 'cache-control': 'no-store' } });
  }

  const link = linkIn(await sharedText(req));
  if (!link) return new Response('No link found in what was shared.', { status: 400 });
  const { blobs } = await store.list({ prefix });
  if (blobs.length >= MAX_WAITING) return new Response('Open WatchLater+ first: too many links are waiting.', { status: 429 });
  const key = prefix + Date.now() + '-' + Math.random().toString(36).slice(2, 8);
  await store.setJSON(key, { u: link });
  return new Response('Saved to WatchLater+', { headers: { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store' } });
}
