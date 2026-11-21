// WatchLater+ web: /.netlify/functions/preview?url=<video link>
// Answers with the video's title and thumbnail so the web app can show them.
import { preview } from '../shared/preview-core.mjs';

export default async (req) => {
  if (req.method !== 'GET') return new Response('Use GET', { status: 405 });
  const link = new URL(req.url).searchParams.get('url') || '';
  if (!link || link.length > 2000) return Response.json({ ok: true, t: '', d: '', i: '', g: 'No link given' }, { status: 400 });
  const out = await preview(link, { deadline: Date.now() + 20000 });
  return Response.json(out, { headers: { 'cache-control': 'no-store' } });
};
