// WatchLater+: /.netlify/functions/icon?h=threads.net
// The small icon of a site, so each saved video shows which app it came from.
import { http, imageType } from '../shared/preview-core.mjs';

const OK = /^(?=.{3,100}$)([a-z0-9-]+\.)+[a-z]{2,}$/;

export default async (req) => {
  const h = (new URL(req.url).searchParams.get('h') || '').toLowerCase();
  if (!OK.test(h)) return new Response('', { status: 400 });
  const ctx = { fetch, deadline: Date.now() + 9000, log: [] };
  const IMAGE = 'image/png,image/x-icon,image/*;q=0.8,*/*;q=0.5';
  for (const src of ['https://www.google.com/s2/favicons?sz=64&domain=' + h, 'https://icons.duckduckgo.com/ip3/' + h + '.ico']) {
    const r = await http(ctx, src, { max: 200000, accept: IMAGE });
    if (!r.body || r.body.cut || r.body.length < 60) continue;
    const type = imageType(r.body, r.type) || (src.endsWith('.ico') ? 'image/x-icon' : '');
    if (!type) continue;
    return new Response(r.body, { headers: { 'content-type': type, 'cache-control': 'public, max-age=2592000' } });
  }
  return new Response('', { status: 404, headers: { 'cache-control': 'public, max-age=86400' } });
};
